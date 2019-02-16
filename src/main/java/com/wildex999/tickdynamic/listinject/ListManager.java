package com.wildex999.tickdynamic.listinject;

import com.wildex999.tickdynamic.TickDynamicConfig;
import com.wildex999.tickdynamic.TickDynamicMod;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ITickable;
import net.minecraft.world.World;
import net.minecraftforge.common.config.ConfigCategory;
import org.apache.commons.lang3.NotImplementedException;

import java.util.*;

/*
 * Written by: Wildex999
 *
 * Overrides ArrayList to act as an replacement for loadedEntityList and loadedTileEntityList.
 */

public class ListManager<T extends EntityObject> implements List<T> {
	protected World world;
	protected EntityType entityType;

	protected HashSet<EntityGroup> localGroups; //Groups local to the world this list is part of
	protected Map<Class, EntityGroup> groupMap; //Map of Class to Group
	protected EntityGroup ungroupedEntities;
	protected Queue<EntityObject> queuedEntities; //Entities awaiting grouping, ticked as part of ungroupedEntities
	protected List<EntityPlayer> playerEntities; //List of players that should tick every server tick

	protected CustomProfiler customProfiler;

	protected int entityCount; //Real count of entities combined in all groups
	protected int age; //Used to invalidate iterators if list changes

	public ListManager(World world, EntityType type) {
		this.world = world;
		this.customProfiler = (CustomProfiler) world.profiler;
		this.entityType = type;
		localGroups = new HashSet<>();
		groupMap = new HashMap<>();
		playerEntities = new ArrayList<>();
		queuedEntities = new ArrayDeque<>();

		entityCount = 0;
		age = 0;

		TickDynamicMod.logDebug("Initializing " + type + " list for world: " + world.provider.getDimensionType().getName() + "(DIM" + world.provider.getDimension() + ")");

		//Add groups from config
		loadLocalGroups();
		loadGlobalGroups();

		//Set default group for ungrouped
		if (type == EntityType.Entity)
			ungroupedEntities = TickDynamicMod.instance.getWorldEntityGroup(world, "entity", type, false, false);
		else
			ungroupedEntities = TickDynamicMod.instance.getWorldEntityGroup(world, "tileentity", type, false, false);

		if (ungroupedEntities == null || !localGroups.contains(ungroupedEntities))
			throw new RuntimeException("TickDynamic Assert failure: Could not find " + type + " group during world initialization!");

		createGroupMap();
	}

	//Add any local groups which are not already loaded
	private void loadLocalGroups() {
		//Add local groups from config
		ConfigCategory config = TickDynamicMod.instance.getWorldConfigCategory(world);
		Iterator<ConfigCategory> localIt;
		for (localIt = config.getChildren().iterator(); localIt.hasNext(); ) {
			ConfigCategory localGroupCategory = localIt.next();
			String name = localGroupCategory.getName();
			EntityGroup localGroup = TickDynamicMod.instance.getWorldEntityGroup(world, name, entityType, true, true);
			if (localGroup.getGroupType() != entityType || localGroups.contains(localGroup))
				continue;

			TickDynamicMod.logDebug("Load local group: " + name);
			localGroups.add(localGroup);
			localGroup.list = this;
		}
	}

	//Add a copy of any global groups who are not already loaded
	private void loadGlobalGroups() {
		ConfigCategory config = TickDynamicMod.instance.config.getCategory("groups");
		Iterator<ConfigCategory> globalIt;
		for (globalIt = config.getChildren().iterator(); globalIt.hasNext(); ) {
			ConfigCategory groupCategory = globalIt.next();
			String name = groupCategory.getName();
			EntityGroup globalGroup = TickDynamicMod.instance.getEntityGroup("groups." + name);

			if (globalGroup == null || globalGroup.getGroupType() != entityType)
				continue;

			//Get or create the local group as a copy of the global, but without a world config entry.
			//Will inherit config from the global group.
			EntityGroup localGroup = TickDynamicMod.instance.getWorldEntityGroup(world, name, entityType, true, false);
			if (localGroups.contains(localGroup))
				continue; //Local group already defined

			TickDynamicMod.logDebug("Load global group: " + name);
			localGroups.add(localGroup);
			localGroup.list = this;
		}
	}

	//Create new Class to Group map
	public void createGroupMap() {
		TickDynamicMod.logDebug("Creating Group map");
		groupMap.clear();

		//Create map of ID to group
		for (EntityGroup group : localGroups) {
			Set<Class> entries = group.getEntityEntries();
			for (Class entityClass : entries) {
				if (TickDynamicMod.debugGroups) {
					String localPath = group.getConfigEntry();
					if (localPath == null)
						localPath = "-";
					String parentPath = "None";
					if (group.base != null)
						parentPath = group.base.getConfigEntry();
					TickDynamicMod.logInfo("Mapping: " + entityClass + " -> " + localPath + "(Global: " + parentPath + ")");
				}
				groupMap.put(entityClass, group);
			}
		}

		TickDynamicMod.logDebug("Done!");
	}

	//Re-create groups from config, and move any entities in/out due to change
	public void reloadGroups() {
		//TODO: Do partial updates each tick to not stop the world, I.e 1% of groups per tick?

		TickDynamicMod.logDebug("Reloading Groups!");

		//Reload config, marking for removal those who no longer exists
		TickDynamicConfig.loadGroups("worlds.dim" + world.provider.getDimension());

		//Move all EntityObjects to new list for later resorting into new groups
		ArrayList<EntityObject> entityList = new ArrayList<>(entityCount);
		Iterator<EntityGroup> groupIterator = localGroups.iterator();
		while (groupIterator.hasNext()) {
			EntityGroup group = groupIterator.next();
			entityList.addAll(group.entities);
			group.clearEntities();

			//Group was removed from config
			if (!group.valid || (group.base != null && !group.base.valid))
				groupIterator.remove();
			else
				group.readConfig(false);
		}

		//Load any new groups
		loadLocalGroups();
		loadGlobalGroups();

		//Recreate the Group Map in case the groups have changed
		createGroupMap();

		//Re-sort entities into the new groups
		for (EntityObject entity : entityList)
			assignToGroup(entity);
	}

	//Assign the given EntityObject to an appropriate group
	public void assignToGroup(EntityObject object) {
		if (object == null) {
			TickDynamicMod.logError("Error: Could not assign null object to group!");
			return;
		}

		EntityGroup group = object.TD_entityGroup;
		if (group != null)
			group.removeEntity(object);

		group = groupMap.get(object.getClass());
		if (group == null) {
			if (TickDynamicMod.debugGroups)
				TickDynamicMod.logDebug("Adding Entity: " + object.getClass() + " -> Ungrouped(" + entityType + ")");
			ungroupedEntities.addEntity(object);
		} else {
			if (TickDynamicMod.debugGroups)
				TickDynamicMod.logDebug("Adding Entity: " + object.getClass() + " -> " + group.getName());
			group.addEntity(object);
		}
	}

	public int getAge() {
		return age;
	}

	//Get a new iterator for the local groups
	public Iterator<EntityGroup> getGroupIterator() {
		return localGroups.iterator();
	}

	@Override
	public boolean add(EntityObject element) {
		if (element.TD_entityGroup != null)
			return false;
		if (element.TD_selfTileEntity != null)
			if (!(element.TD_selfTileEntity instanceof ITickable))
				return false;//Don't add non-tickable tile entities

		//TODO: Queue and add over time
		//ungroupedEntities.addEntity(element);
		//queuedEntities.add(element); //Add to queue for later insertion into a group
		assignToGroup(element);

		entityCount++;
		//age++; //We allow adding without aging, as it does not disrupt the iterators

		return true;
	}

	@Override
	public void add(int index, EntityObject element) {
		add(element); //We ignore index(Not used in Minecraft, and doesn't make sense for us)
	}

	@Override
	public boolean addAll(Collection<? extends T> c) {
		//TODO: Actually verify that the list did change before returning true
		for (EntityObject element : c)
			add(element);
		return true;
	}

	@Override
	public boolean addAll(int index, Collection<? extends T> c) {
		return addAll(c);
	}

	@Override
	public void clear() {
		for (EntityGroup group : localGroups) {
			group.clearEntities();
		}
		entityCount = 0;
		age++;

		TickDynamicMod.logDebug("Cleared all loaded object of the type " + entityType + " from world: " + (world == null ? "Unknown" : world.provider.getDimensionType().getName()));
	}

	@Override
	public boolean contains(Object object) {
		if (!(object instanceof EntityObject)) {
			TickDynamicMod.logWarn("Trying to remove: " + object + " but not instanceof class EntityObject");
			return false;
		}
		EntityObject entityObject = (EntityObject) object;
		return entityObject.TD_entityGroup != null && entityObject.TD_entityGroup.list == this;
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		for (Object obj : c) {
			if (!contains(obj))
				return false;
		}
		return true;
	}

	@Override
	public T get(int index) {
		if (index >= entityCount || index < 0)
			throw new IndexOutOfBoundsException("Tried to get index: " + index + ", but size is: " + entityCount);
		//Walk through groups, adding their size to index, until we reach the group with the index
		//Note: localGroups's order is not guaranteed to remain the same after a change.
		int offset = 0;
		for (EntityGroup group : localGroups) {
			if (offset + group.getEntityCount() > index)
				return (T) group.entities.get(index - offset);
			offset += group.getEntityCount();
		}
		throw new IndexOutOfBoundsException("Reached end of groups before finding index: " + index);
	}

	@Override
	public int indexOf(Object o) {
		if (!(o instanceof EntityObject))
			return -1;

		EntityObject obj = (EntityObject) o;
		if (obj.TD_entityGroup == null || obj.TD_entityGroup.list != this)
			return -1;

		//Same strategy as get(index), jump groups, adding their size until we reach the group containing the Object
		int offset = 0;
		for (EntityGroup group : localGroups) {
			if (obj.TD_entityGroup == group) {
				int index = group.entities.indexOf(obj);
				if (index == -1)
					return -1;
				return offset + index;
			}
			offset += group.getEntityCount();
		}

		return -1;
	}

	@Override
	public boolean isEmpty() {
		return entityCount == 0;
	}

	@Override
	public int lastIndexOf(Object o) {
		if (!(o instanceof EntityObject))
			return -1;

		EntityObject obj = (EntityObject) o;
		if (obj.TD_entityGroup == null || obj.TD_entityGroup.list != this)
			return -1;

		//Same strategy as get(index), jump groups, adding their size until we reach the group containing the Object
		int offset = 0;
		int lastIndex = -1;
		for (EntityGroup group : localGroups) {
			if (obj.TD_entityGroup == group) {
				int index = group.entities.indexOf(obj);
				if (index == -1)
					return -1;
				lastIndex = offset + index;
			}
			offset += group.getEntityCount();
		}

		return lastIndex;
	}

	@Override
	public Iterator<T> iterator() {
		if (customProfiler.reachedTile) {
			customProfiler.reachedTile = false; //Reset flag
			return (Iterator<T>) new EntityIteratorTimed(this, getAge());
		}

		return (Iterator<T>) new EntityIterator(this, getAge());
	}

	@Override
	public ListIterator<T> listIterator() {
		throw new NotImplementedException("listIterator is not implemented in TickDynamic's List implementation!");
	}

	@Override
	public ListIterator<T> listIterator(int index) {
		throw new NotImplementedException("listIterator(index) is not implemented in TickDynamic's List implementation!");
	}

	@Override
	public boolean remove(Object object) {
		if (!contains(object)) {
			//TickDynamicMod.logWarn("Failed to remove: " + object + " as it does not exist in the list.");
			return false;
		}

		EntityObject entityObject = (EntityObject) object;
		if (entityObject.TD_entityGroup.removeEntity(entityObject)) {
			entityCount--;
			age++;
			return true;
		}
		TickDynamicMod.logError("Failed to remove: " + object + " unknown reason!");

		return false;
	}

	@Override
	public T remove(int index) {
		Thread.dumpStack();
		//TickDynamicMod.logDebug("Debug Warning: Using slow remove of objects(Remove by index)!");
		T entityObject = get(index);
		if (remove(entityObject))
			return entityObject;
		return null;
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		//TODO: Actually make sure any was removed before returning true
		for (Object obj : c)
			remove(obj);
		return true;
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		//TODO: Actually implement this at some time(No one uses it as far as I can see)
		throw new NotImplementedException("retainAll is not implemented in TickDynamic's List implementation!");
	}

	@Override
	public T set(int index, EntityObject element) {
		//TODO: I can see absolutely no use for this in Minecraft, and any implementation would be slow(-ish) and inaccurate
		throw new NotImplementedException("set is not implemented in TickDynamic's List implementation!");
	}

	@Override
	public int size() {
		return entityCount;
	}

	@Override
	public List<T> subList(int fromIndex, int toIndex) {
		throw new NotImplementedException("subList is not implemented in TickDynamic's List implementation!");
	}

	@Override
	public Object[] toArray() {
		//Construct an array from all the groups
		TickDynamicMod.logDebug("SLOW toArray call on Entity/TileEntity list!");
		Object[] objects = new Object[entityCount];
		int offset = 0;
		for (EntityGroup group : localGroups) {
			System.arraycopy(group.entities.toArray(), 0, objects, offset, group.entities.size());
			offset += group.entities.size();
		}
		return objects;
	}

	@Override
	public <T> T[] toArray(T[] a) {
		throw new NotImplementedException("toArray(T[]) is not implemented in TickDynamic's List implementation!");
	}
}

package dev.kausik.shaders.runtime;

import dev.kausik.shaders.pack.MaterialMappings;
import dev.kausik.shaders.pack.ShaderPackException;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Resolves pack selectors against the frozen game registries once. Chunk workers then perform an
 * immutable identity lookup, with no property serialization, selector matching, or allocations.
 */
public final class BlockMaterialMap implements ToIntFunction<BlockState> {
  private final Map<BlockState, Integer> blocks;
  private final Map<Item, Integer> items;
  private final Map<EntityType<?>, Integer> entities;

  public BlockMaterialMap(MaterialMappings mappings) throws ShaderPackException {
    Map<BlockState, Integer> states = new IdentityHashMap<>();
    for (Block block : BuiltInRegistries.BLOCK) {
      String identifier = BuiltInRegistries.BLOCK.getKey(block).toString();
      for (BlockState state : block.getStateDefinition().getPossibleStates()) {
        Map<String, String> properties = new HashMap<>();
        for (Property<?> property : state.getProperties()) {
          properties.put(property.getName(), serialize(state, property));
        }
        states.put(state, mappings.blockId(identifier, properties));
      }
    }
    Map<Item, Integer> itemIds = new IdentityHashMap<>();
    for (Item item : BuiltInRegistries.ITEM) {
      itemIds.put(item, mappings.itemId(BuiltInRegistries.ITEM.getKey(item).toString()));
    }
    Map<EntityType<?>, Integer> entityIds = new IdentityHashMap<>();
    for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
      entityIds.put(type, mappings.entityId(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()));
    }
    blocks = Collections.unmodifiableMap(states);
    items = Collections.unmodifiableMap(itemIds);
    entities = Collections.unmodifiableMap(entityIds);
  }

  private static <T extends Comparable<T>> String serialize(
      BlockState state, Property<T> property) {
    return property.getName(state.getValue(property));
  }

  @Override
  public int applyAsInt(BlockState state) {
    return blocks.getOrDefault(state, -1);
  }

  public int stateCount() {
    return blocks.size();
  }

  /** Includes the complete frozen registry; any future unmapped state resolves to -1. */
  public boolean allBlockIdsFitSigned16() {
    for (int id : blocks.values()) {
      if (id < Short.MIN_VALUE || id > Short.MAX_VALUE) return false;
    }
    return true;
  }

  public int itemId(ItemStack stack) {
    return stack.isEmpty() ? -1 : itemId(stack.getItem());
  }

  public int itemId(Item item) {
    return items.getOrDefault(item, -1);
  }

  public int entityId(Entity entity) {
    return entityTypeId(entity.getType());
  }

  public int entityTypeId(EntityType<?> type) {
    return entities.getOrDefault(type, -1);
  }
}

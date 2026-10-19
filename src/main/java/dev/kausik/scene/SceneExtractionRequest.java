package dev.kausik.scene;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;

/** Separate inputs for vanilla's dirty-section and block-entity extraction loops. */
public record SceneExtractionRequest(
    SceneViewRequest view,
    List<RenderSection> cameraSections,
    Purpose purpose,
    SectionUpdateTracker updateTracker) {
  public enum Purpose {
    DIRTY_SECTIONS,
    BLOCK_ENTITIES
  }

  public SceneExtractionRequest {
    Objects.requireNonNull(view);
    Objects.requireNonNull(cameraSections);
    Objects.requireNonNull(purpose);
    if (purpose == Purpose.DIRTY_SECTIONS) Objects.requireNonNull(updateTracker);
  }

  /** Read current state again; sparse membership never replaces vanilla's live state checks. */
  public boolean matches(RenderSection section) {
    if (purpose == Purpose.BLOCK_ENTITIES) {
      return !section.getSectionMesh().getRenderableBlockEntities().isEmpty();
    }
    var state = updateTracker.getDirtyState(section.getSectionNode());
    return state != null && state.isDirty();
  }

  /** Camera order first, then the source order of additional shadow sections, without duplicates. */
  public List<RenderSection> merge(Iterable<RenderSection> extraSections) {
    var selected = new ArrayList<RenderSection>();
    var included = new IdentityHashMap<RenderSection, Boolean>();
    for (var section : cameraSections) {
      if (matches(section) && included.put(section, Boolean.TRUE) == null) selected.add(section);
    }
    for (var section : extraSections) {
      if (!included.containsKey(section) && matches(section)) {
        included.put(section, Boolean.TRUE);
        selected.add(section);
      }
    }
    return List.copyOf(selected);
  }
}

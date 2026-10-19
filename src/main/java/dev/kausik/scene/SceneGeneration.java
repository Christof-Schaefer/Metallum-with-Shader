package dev.kausik.scene;

/**
 * Versions of world resources and pack-defined geometry requirements, owned by the render thread.
 */
public record SceneGeneration(long world, long materials) {}

package org.groundzero.mesh.app.node

/**
 * The role this device plays in the mesh, switchable at runtime.
 *
 * - [NODE] originates and relays; shows the SOS UI.
 * - [RELAY] carry-only; no local SOS, keeps the store-and-forward buffer warm.
 * - [GATEWAY] also runs the L3 responder server and hotspot.
 */
enum class MeshRole { NODE, RELAY, GATEWAY }

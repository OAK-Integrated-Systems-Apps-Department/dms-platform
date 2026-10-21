package com.oak.dms.signature.enums;

/**
 * How a user chose to represent their visual signature - cosmetic only, see
 * {@code UserVisualSignature}'s own doc comment. Never confused with the real
 * cryptographic signature this accompanies.
 * <p>
 * {@link #drawn} (hand-drawn on a canvas) and {@link #uploaded} (a photo/
 * scan, background made transparent client-side) are both stored the exact
 * same way - an image in {@code image_png}/{@code visual_signature_image} -
 * and validated identically (everything except {@link #typed} needs an
 * image). They're kept as separate values purely so the frontend can
 * remember which editor to show when re-opening an already-saved one,
 * rather than always defaulting back to the drawing canvas.
 */
public enum VisualSignatureKind {
    typed, drawn, uploaded
}

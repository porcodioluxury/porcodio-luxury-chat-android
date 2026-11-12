package org.telegram.messenger;

import android.graphics.Color;

/** Porcodio Luxury Chat: helper colori (palette solo grigi e grigio-blu). */
public class PcColors {
    /** Toglie la saturazione (resta un leggero grigio-blu 220deg) mantenendo la luminosità. */
    public static int desat(int argb) {
        float[] hsl = new float[3];
        androidx.core.graphics.ColorUtils.colorToHSL(argb, hsl);
        if (hsl[1] <= 0.25f) {
            return argb;
        }
        hsl[0] = 220f;
        hsl[1] = 0.10f;
        int rgb = androidx.core.graphics.ColorUtils.HSLToColor(hsl);
        return (argb & 0xFF000000) | (rgb & 0x00FFFFFF);
    }
}

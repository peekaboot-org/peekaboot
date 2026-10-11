package org.peekaboot.testingapp.ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How far apart two CSS colours look to a dichromat: each colour is passed through Machado,
 * Oliveira and Fernandes (2009)'s simulation at full severity, in linear sRGB as the paper
 * defines it, and the results are compared with CIEDE2000 under D65.
 */
final class ColourVision {

    /** The three dichromacies, each a row-major 3x3 matrix over linear sRGB (Machado 2009, severity 1.0). */
    enum Deficiency {
        PROTANOPIA,
        DEUTERANOPIA,
        TRITANOPIA;

        private double[] matrix() {
            return switch (this) {
                case PROTANOPIA ->
                    new double[] {
                        0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998
                    };
                case DEUTERANOPIA ->
                    new double[] {
                        0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.01182, 0.04294, 0.968881
                    };
                case TRITANOPIA ->
                    new double[] {
                        1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.3039
                    };
            };
        }

        private double[] simulate(double[] linearRgb) {
            double[] matrix = matrix();
            double[] out = new double[3];
            for (int row = 0; row < 3; row++) {
                double sum = 0;
                for (int col = 0; col < 3; col++) {
                    sum += matrix[row * 3 + col] * linearRgb[col];
                }
                out[row] = Math.clamp(sum, 0.0, 1.0);
            }
            return out;
        }
    }

    private static final Pattern RGB = Pattern.compile("rgba?\\((\\d+), (\\d+), (\\d+)");

    private ColourVision() {}

    /** CIEDE2000 between {@code first} and {@code second}, both {@code getComputedStyle} colours, as {@code deficiency} sees them. */
    static double difference(String first, String second, Deficiency deficiency) {
        return ciede2000(lab(deficiency.simulate(linearRgb(first))), lab(deficiency.simulate(linearRgb(second))));
    }

    private static double[] linearRgb(String cssColour) {
        Matcher matcher = RGB.matcher(cssColour);
        if (!matcher.lookingAt()) {
            throw new IllegalArgumentException("not an rgb() colour: " + cssColour);
        }
        double[] rgb = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(matcher.group(i + 1)) / 255.0;
            rgb[i] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return rgb;
    }

    /** CIELAB under D65, from linear sRGB. */
    private static double[] lab(double[] rgb) {
        double x = (0.4124564 * rgb[0] + 0.3575761 * rgb[1] + 0.1804375 * rgb[2]) / 0.95047;
        double y = 0.2126729 * rgb[0] + 0.7151522 * rgb[1] + 0.0721750 * rgb[2];
        double z = (0.0193339 * rgb[0] + 0.1191920 * rgb[1] + 0.9503041 * rgb[2]) / 1.08883;
        double fx = labF(x);
        double fy = labF(y);
        double fz = labF(z);
        return new double[] {116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double labF(double t) {
        double epsilon = 216.0 / 24389;
        double kappa = 24389.0 / 27;
        return t > epsilon ? Math.cbrt(t) : (kappa * t + 16) / 116;
    }

    /** Sharma, Wu and Dalal (2005)'s formulation, with kL = kC = kH = 1. */
    private static double ciede2000(double[] first, double[] second) {
        double c1 = Math.hypot(first[1], first[2]);
        double c2 = Math.hypot(second[1], second[2]);
        double meanC7 = Math.pow((c1 + c2) / 2, 7);
        double g = 0.5 * (1 - Math.sqrt(meanC7 / (meanC7 + Math.pow(25, 7))));
        double a1 = (1 + g) * first[1];
        double a2 = (1 + g) * second[1];
        double cp1 = Math.hypot(a1, first[2]);
        double cp2 = Math.hypot(a2, second[2]);
        double hp1 = hueDegrees(first[2], a1);
        double hp2 = hueDegrees(second[2], a2);

        double deltaL = second[0] - first[0];
        double deltaC = cp2 - cp1;
        double deltaHue = 0;
        if (cp1 * cp2 != 0) {
            deltaHue = hp2 - hp1;
            if (deltaHue > 180) {
                deltaHue -= 360;
            } else if (deltaHue < -180) {
                deltaHue += 360;
            }
        }
        double deltaH = 2 * Math.sqrt(cp1 * cp2) * Math.sin(Math.toRadians(deltaHue / 2));

        double meanL = (first[0] + second[0]) / 2;
        double meanC = (cp1 + cp2) / 2;
        double meanH = hp1 + hp2;
        if (cp1 * cp2 != 0) {
            if (Math.abs(hp1 - hp2) <= 180) {
                meanH /= 2;
            } else {
                meanH = meanH < 360 ? (meanH + 360) / 2 : (meanH - 360) / 2;
            }
        }
        double t = 1
                - 0.17 * Math.cos(Math.toRadians(meanH - 30))
                + 0.24 * Math.cos(Math.toRadians(2 * meanH))
                + 0.32 * Math.cos(Math.toRadians(3 * meanH + 6))
                - 0.20 * Math.cos(Math.toRadians(4 * meanH - 63));
        double lightnessOffset = Math.pow(meanL - 50, 2);
        double sl = 1 + 0.015 * lightnessOffset / Math.sqrt(20 + lightnessOffset);
        double sc = 1 + 0.045 * meanC;
        double sh = 1 + 0.015 * meanC * t;
        double meanC7p = Math.pow(meanC, 7);
        double rotation = -2
                * Math.sqrt(meanC7p / (meanC7p + Math.pow(25, 7)))
                * Math.sin(Math.toRadians(60 * Math.exp(-Math.pow((meanH - 275) / 25, 2))));

        double l = deltaL / sl;
        double c = deltaC / sc;
        double h = deltaH / sh;
        return Math.sqrt(l * l + c * c + h * h + rotation * c * h);
    }

    private static double hueDegrees(double b, double a) {
        if (a == 0 && b == 0) {
            return 0;
        }
        double degrees = Math.toDegrees(Math.atan2(b, a));
        return degrees < 0 ? degrees + 360 : degrees;
    }
}

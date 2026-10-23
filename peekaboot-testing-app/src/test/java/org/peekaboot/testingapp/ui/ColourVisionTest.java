package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class ColourVisionTest {

    @Test
    void blackOnWhiteIsTheWidestContrastThereIs() {
        assertThat(ColourVision.contrastRatio("rgb(0, 0, 0)", "rgb(255, 255, 255)"))
                .isCloseTo(21.0, within(1e-9));
    }

    /** #767676 on white, the lightest grey that still clears AA's 4.5:1. */
    @Test
    void theAaBoundaryGreyMeasuresItsPublishedRatioWhicheverSideIsInk() {
        assertThat(ColourVision.contrastRatio("rgb(118, 118, 118)", "rgb(255, 255, 255)"))
                .isCloseTo(4.54, within(0.005));
        assertThat(ColourVision.contrastRatio("rgb(255, 255, 255)", "rgb(118, 118, 118)"))
                .isCloseTo(4.54, within(0.005));
    }

    @Test
    void anRgbaColourIsReadByItsColourChannels() {
        assertThat(ColourVision.contrastRatio("rgba(0, 0, 0, 0.5)", "rgb(255, 255, 255)"))
                .isCloseTo(21.0, within(1e-9));
    }
}

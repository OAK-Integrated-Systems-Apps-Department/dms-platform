package com.oak.dms.dashboard;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code dms.impact.*} - the assumptions behind the dashboard's Digital Impact estimates.
 * They live here, not in the browser, so an organisation can set its own (its paper, its
 * suppliers' published factors) without a code change. The defaults are deliberately
 * conservative - the low end of published ranges - so the figures understate rather than
 * overstate. {@code calculationVersion} goes up whenever the method itself changes.
 */
@Component
@ConfigurationProperties(prefix = "dms.impact")
@Getter
@Setter
public class ImpactProperties {

    /** Changes when the calculation method changes, so old screenshots can be told apart. */
    private String calculationVersion = "1";

    private String paperSize = "A4";
    private double paperWidthMm = 210;
    private double paperHeightMm = 297;
    /** Paper weight, grams per square metre. */
    private double paperGsm = 80;
    /** Pages printed on one sheet - 2 for double-sided printing. */
    private int pagesPerSheet = 2;

    /** kg CO2e per kg of paper over its life cycle. */
    private double co2eKgPerKgPaper = 1.08;
    private String co2eSource =
            "EPA Victoria, office copy paper: 1.08-1.52 kg CO2e/kg - the low end of the range";

    /** Litres of water footprint per kg of paper. */
    private double waterLitresPerKgPaper = 300;
    private String waterSource =
            "Water Footprint Network, printing and writing paper: 300-2,600 L/kg - the low end of the range";

    /** Grams of paper per printed page, from the paper size, weight and pages per sheet. */
    public double gramsPerPage() {
        double sheetSquareMetres = (paperWidthMm / 1000) * (paperHeightMm / 1000);
        return sheetSquareMetres * paperGsm / Math.max(1, pagesPerSheet);
    }
}

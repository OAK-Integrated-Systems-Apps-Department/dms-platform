package com.oak.dms.search.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DidYouMeanTest {

    @Test
    void correctsAMisspellingFromTheGivenVocabulary() {
        assertThat(DidYouMean.suggest("suplier contrct", List.of("Supplier contract", "notes.txt"))).isEqualTo("supplier contract");
        assertThat(DidYouMean.suggest("quarterley", List.of("Quarterly revenue report"))).isEqualTo("quarterly");
    }

    @Test
    void keepsWordsThatAreAlreadyRightAndOnlyFixesTheWrongOnes() {
        assertThat(DidYouMean.suggest("supplier contrct", List.of("Supplier contract"))).isEqualTo("supplier contract");
    }

    @Test
    void nothingToImproveMeansNoSuggestion() {
        assertThat(DidYouMean.suggest("supplier contract", List.of("Supplier contract"))).isNull();
        assertThat(DidYouMean.suggest("xylophone", List.of("Supplier contract"))).as("too far from anything").isNull();
        assertThat(DidYouMean.suggest("anything", List.of())).as("an empty vocabulary can suggest nothing").isNull();
    }

    @Test
    void ignoresAccentsAndCase() {
        assertThat(DidYouMean.suggest("cafee", List.of("Café menu"))).isEqualTo("cafe");
    }

    @Test
    void shortWordsAreLeftAlone() {
        assertThat(DidYouMean.suggest("an of", List.of("To be or not"))).isNull();
    }

    @Test
    void levenshteinDistance() {
        assertThat(DidYouMean.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(DidYouMean.distance("same", "same")).isZero();
        assertThat(DidYouMean.distance("", "abc")).isEqualTo(3);
    }
}

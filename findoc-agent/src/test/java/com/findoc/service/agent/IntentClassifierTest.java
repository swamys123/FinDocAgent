package com.findoc.service.agent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class IntentClassifierTest {
    private final IntentClassifier classifier = new IntentClassifier();

    @ParameterizedTest
    @CsvSource({
        "Compare the two agreements,COMPARE",
        "What is the difference between the clauses?,COMPARE",
        "Contrast their notice periods,COMPARE",
        "Prepare an analysis of termination,REPORT",
        "Show the compliance findings,REPORT",
        "Summarize the obligations,SUMMARISE",
        "Provide a summary,SUMMARISE",
        "Give an overview of the policy,SUMMARISE",
        "What is the termination notice?,LOOKUP"
    })
    void classifiesCommonIntentPhrases(String query, String expectedIntent) {
        assertThat(classifier.classify(query)).isEqualTo(expectedIntent);
    }

    @ParameterizedTest
    @CsvSource({
        "Compare the report summaries,COMPARE",
        "Prepare a report summary,REPORT",
        "The reporter mentioned an overview, SUMMARISE"
    })
    void appliesExplicitIntentPrecedenceAndWordBoundaries(String query, String expectedIntent) {
        assertThat(classifier.classify(query)).isEqualTo(expectedIntent);
    }
}
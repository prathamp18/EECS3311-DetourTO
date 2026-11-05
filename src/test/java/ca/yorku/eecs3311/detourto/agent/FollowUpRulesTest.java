package ca.yorku.eecs3311.detourto.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FollowUpRulesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "leave 15 minutes later           | CHANGE_TIME        | +15",
        "Can I go 10 min earlier?         | CHANGE_TIME        | -10",
        "what if I leave an hour later    | CHANGE_TIME        | +60",
        "leave at 9:30                    | CHANGE_TIME        | depart@09:30",
        "I need to arrive by 10am         | CHANGE_TIME        | arrive@10:00",
        "be there by 5                    | CHANGE_TIME        | arrive@17:00",
        "avoid line 2                     | AVOID_ROUTE        | 2",
        "no 510 please                    | AVOID_ROUTE        | 510",
        "no streetcars                    | AVOID_MODE         | STREETCAR",
        "avoid the subway                 | AVOID_MODE         | SUBWAY",
        "fewest transfers please          | CHANGE_STRATEGY    | fewest-transfers",
        "I'd rather walk less             | CHANGE_STRATEGY    | least-walking",
        "I'm in a wheelchair              | CHANGE_STRATEGY    | accessible",
        "what about Union instead?        | CHANGE_DESTINATION | Union",
        "undo                             | UNDO               | ''",
        "redo                             | REDO               | ''",
    })
    void commonFollowUps(String text, IntentKind kind, String value) {
        FollowUpIntent intent = FollowUpRules.parse(text);
        assertEquals(kind, intent.kind(), text);
        assertEquals(value, intent.value(), text);
    }

    @ParameterizedTest
    @CsvSource({"what's the weather", "''"})
    void anythingElseIsUnknown(String text) {
        assertEquals(IntentKind.UNKNOWN, FollowUpRules.parse(text).kind());
    }
}

package com.oak.dms.comment;

import com.oak.dms.comment.helper.MentionParser;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MentionParserTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void readsDistinctIdsInOrder_ignoringTheCosmeticName() {
        String body = "Hi @[Nimal Perera](user:" + a + ") and @[B](user:" + b + "), cc @[Nimal P.](user:" + a + ")";
        assertThat(MentionParser.mentionedUserIds(body)).containsExactly(a, b);
    }

    @Test
    void plainTextAndMalformedTokensAreNotMentions() {
        assertThat(MentionParser.mentionedUserIds("mail me @nimal or @[x](user:not-a-uuid) or [y](user:" + a + ")"))
                .isEmpty();
        assertThat(MentionParser.mentionedUserIds("@[](user:" + a + ")")).isEmpty();
        assertThat(MentionParser.mentionedUserIds(null)).isEmpty();
    }
}

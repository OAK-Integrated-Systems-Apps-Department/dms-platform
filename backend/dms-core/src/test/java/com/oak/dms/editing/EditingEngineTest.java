package com.oak.dms.editing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditingEngineTest {

    /** The shape of Collabora's /hosting/discovery - note urlsrc names the ENGINE's own host, which the browser can't use. */
    private static final String DISCOVERY = """
            <?xml version="1.0" encoding="utf-8"?>
            <wopi-discovery>
              <net-zone name="external-http">
                <app name="application/vnd.openxmlformats-officedocument.wordprocessingml.document">
                  <action name="edit" ext="docx" urlsrc="http://collabora:9980/browser/abc123/cool.html?"/>
                  <action name="view" ext="docx" urlsrc="http://collabora:9980/browser/abc123/cool.html?view=1&amp;"/>
                </app>
                <app name="application/vnd.oasis.opendocument.text">
                  <action name="edit" ext="ODT" urlsrc="http://collabora:9980/browser/abc123/cool.html?x=1"/>
                </app>
                <app name="application/pdf">
                  <action name="view" ext="pdf" urlsrc="http://collabora:9980/browser/abc123/cool.html?"/>
                </app>
                <app name="application/x-by-mime-type-only">
                  <action name="edit" ext="" urlsrc="http://collabora:9980/browser/abc123/cool.html?m=1"/>
                </app>
              </net-zone>
            </wopi-discovery>
            """;

    @Test
    void keepsOnlyEditActions_byLowerCasedExtension_andDropsTheEnginesOwnHost() {
        Map<String, String> byExt = EditingEngine.parse(DISCOVERY.getBytes(StandardCharsets.UTF_8));

        // a view-only type (pdf) is not editable, and an action with no extension at all is not a file type
        assertThat(byExt).containsOnlyKeys("docx", "odt");
        assertThat(byExt.get("docx")).isEqualTo("/browser/abc123/cool.html?"); // path only: the browser uses the public host
        assertThat(byExt.get("odt")).isEqualTo("/browser/abc123/cool.html?x=1&"); // ready for "WOPISrc=" to be appended
    }

    @Test
    void aDocumentWithADoctype_isRefused_soAForgedDiscoveryCannotReadFilesOrCallOut() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <wopi-discovery><net-zone><app><action name="edit" ext="docx" urlsrc="http://c/&x;"/></app></net-zone></wopi-discovery>
                """;
        assertThatThrownBy(() -> EditingEngine.parse(xxe.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EditingEngine.EngineUnavailableException.class);
        assertThatThrownBy(() -> EditingEngine.parse("not xml".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EditingEngine.EngineUnavailableException.class);
    }

    @Test
    void extensionOf_handlesTheAwkwardNames() {
        assertThat(EditingEngine.extensionOf("Contract.DOCX")).isEqualTo("docx");
        assertThat(EditingEngine.extensionOf("archive.tar.gz")).isEqualTo("gz");
        assertThat(EditingEngine.extensionOf("noextension")).isNull();
        assertThat(EditingEngine.extensionOf("trailingdot.")).isNull();
        assertThat(EditingEngine.extensionOf(null)).isNull();
    }

    @Test
    void anEngineThatIsOffOrUnreachable_isUnavailable_notAnError() {
        EditingEngine off = new EditingEngine("", "http://collabora:9980");
        assertThat(off.isConfigured()).isFalse();
        assertThat(off.supportedExtensions()).isEmpty();
        assertThat(off.supports("a.docx")).isFalse();

        // Enabled, but nothing listening: the whole point of CICO-NFR-002 is that this is a clean "unavailable".
        EditingEngine down = new EditingEngine("http://localhost:9980", "http://127.0.0.1:1");
        assertThat(down.isConfigured()).isTrue();
        assertThat(down.supportedExtensions()).isEmpty();
        assertThatThrownBy(down::requireAvailable).isInstanceOf(EditingEngine.EngineUnavailableException.class);
    }
}

package dev.minecraftharness;

import dev.minecraftharness.agent.ModelCatalog;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HelpViewTests {

    private static final HelpView.Status STATUS =
        new HelpView.Status("claude CLI（サブスクリプション）", "cli/opus", "low", "main（まだ空）");

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void rowsFitChatWidth() {
        for (HelpView.Entry e : HelpView.entries()) {
            String line = plain(HelpView.row(e, true));
            assertTrue(HelpView.columns(line) <= HelpView.MAX_COLUMNS, line + " = " + HelpView.columns(line));
        }
        for (int p = 1; p <= HelpView.pageCount(); p++) {
            for (Component c : HelpView.render("0.3.0", STATUS, p, perm -> true)) {
                String line = plain(c);
                assertTrue(HelpView.columns(line) <= HelpView.MAX_COLUMNS, line + " = " + HelpView.columns(line));
            }
        }
    }

    @Test
    void pageOneHasBannerStatusAndBuildCommands() {
        List<Component> page = HelpView.render("0.3.0", STATUS, 1, perm -> true);
        assertTrue(plain(page.get(0)).contains("MinecraftHarness v0.3.0"));
        String all = String.join("\n", page.stream().map(HelpViewTests::plain).toList());
        assertTrue(all.contains("認証") && all.contains("cli/opus") && all.contains("effort") && all.contains("会話"));
        assertTrue(all.contains("▍建築") && all.contains("/mh undo — "));
        assertFalse(all.contains("▍モデル"));
    }

    @Test
    void everyCategoryAppearsOnSomePage() {
        StringBuilder all = new StringBuilder();
        for (int p = 1; p <= HelpView.pageCount(); p++) {
            for (Component c : HelpView.render("0.3.0", STATUS, p, perm -> true)) all.append(plain(c)).append('\n');
        }
        for (String cat : HelpView.CATEGORIES) assertTrue(all.toString().contains("▍" + cat), cat);
        assertTrue(all.toString().contains("/mh effort"));
    }

    @Test
    void clickingARowSuggestsTheCommand() {
        HelpView.Entry effort = HelpView.entries().stream().filter(e -> e.usage().equals("/mh effort")).findFirst().orElseThrow();
        ClickEvent click = HelpView.row(effort, true).clickEvent();
        assertNotNull(click);
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, click.action());
    }

    @Test
    void commandsWithoutPermissionAreGrayAndNotClickable() {
        HelpView.Entry reset = HelpView.entries().stream().filter(e -> e.usage().equals("/mh reset")).findFirst().orElseThrow();
        Component denied = HelpView.row(reset, false);
        assertNull(denied.clickEvent());
        assertNotNull(denied.hoverEvent());
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY, denied.color());
        assertNotNull(HelpView.row(reset, true).clickEvent());
    }

    @Test
    void resolvePageAcceptsNumbersAndCategories() {
        assertEquals(1, HelpView.resolvePage(null));
        assertEquals(2, HelpView.resolvePage("2"));
        assertEquals(2, HelpView.resolvePage("モデル"));
        assertEquals(2, HelpView.resolvePage("session"));
        assertEquals(3, HelpView.resolvePage("admin"));
        assertEquals(0, HelpView.resolvePage("9"));
        assertEquals(0, HelpView.resolvePage("nope"));
    }

    @Test
    void effortWarningOnlyForUnknownModelsAtTopLevels() {
        ModelCatalog.Model builtin = ModelCatalog.all().get(3);
        for (String l : ModelCatalog.effortLevels()) assertNull(ModelCatalog.effortWarning(builtin, l));
        ModelCatalog.Model custom = new ModelCatalog.Model(ModelCatalog.Backend.API, "claude-custom-x", "custom");
        assertNull(ModelCatalog.effortWarning(custom, "high"));
        assertNotNull(ModelCatalog.effortWarning(custom, "max"));
        assertNotNull(ModelCatalog.effortWarning(builtin, "bogus"));
    }
}

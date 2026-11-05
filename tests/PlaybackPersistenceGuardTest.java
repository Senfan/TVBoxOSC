import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Source guards for Android callbacks; these are not device integration tests. */
public final class PlaybackPersistenceGuardTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0], "app/src/main/java/com/github/tvbox/osc");
        for (String entry : new String[]{"ui/activity/PlayActivity.java", "ui/fragment/PlayFragment.java"}) {
            String source = Files.readString(root.resolve(entry), StandardCharsets.UTF_8);
            String success = methodBody(source, "public void onSuccess(int playerType) {");
            expect(success.replaceAll("\\s+", "").equals("retryPolicy.markSuccess(playerType);"),
                    entry + ": automatic success must only mark the retry policy, without saving or clearing the UI override");
            expect(!source.contains("PlayerPreferenceEvent"), entry + ": automatic preference event must stay disconnected");
            expect(source.contains("mController.setEffectivePlayerType(playerType);"),
                    entry + ": startup must still display the actual player");
            String manual = methodBody(source, "public void updatePlayerCfg() {");
            expect(manual.contains("mVodInfo.playerCfg = mVodPlayerCfg.toString();")
                            && manual.contains("new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodPlayerCfg)"),
                    entry + ": manual configuration saving must remain available");
        }
        String detail = Files.readString(root.resolve("ui/activity/DetailActivity.java"), StandardCharsets.UTF_8);
        expect(!detail.contains("PlayerPreferenceEvent"), "DetailActivity: automatic history subscription must stay disconnected");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList labels = factory.newDocumentBuilder().parse(Path.of(args[0],
                "app/src/main/res/layout/player_vod_control_view.xml").toFile()).getElementsByTagName("TextView");
        boolean foundPlayerLabel = false;
        for (int i = 0; i < labels.getLength(); i++) {
            Element label = (Element) labels.item(i);
            if (!"@+id/play_player_txt".equals(label.getAttribute("android:id"))) continue;
            foundPlayerLabel = true;
            String visibility = label.getAttribute("android:visibility");
            expect(visibility.isEmpty() || "visible".equals(visibility), "Actual player label must be visible");
            expect("wrap_content".equals(label.getAttribute("android:layout_width")),
                    "Temporary player label must fit its full text");
            expect("wrap_content".equals(((Element) label.getParentNode()).getAttribute("android:layout_width")),
                    "Player button must expand to fit its temporary label");
        }
        expect(foundPlayerLabel, "Player control must contain its actual player label");
        System.out.println("PlaybackPersistenceGuard: " + assertions + " source checks passed");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        expect(start >= 0 && source.indexOf(signature, start + signature.length()) < 0,
                "Expected exactly one callback: " + signature);
        start += signature.length();
        int end = source.indexOf("\n            }", start);
        expect(end >= 0, "Missing callback closing brace: " + signature);
        return source.substring(start, end);
    }

    private static void expect(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}

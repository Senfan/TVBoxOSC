from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ANDROID = "{http://schemas.android.com/apk/res/android}"
checks = 0


def check(condition, message):
    global checks
    assert condition, message
    checks += 1


for variant in ("layout", "layout-v21"):
    path = ROOT / "app/src/main/res" / variant / "fragment_model.xml"
    root = ET.parse(path).getroot()
    parents = {child: parent for parent in root.iter() for child in parent}
    check("VodPlayerPreferred" not in path.read_text(), variant + ": no outer priority or dangling focus ID")
    check(not any(element.get(ANDROID + "text") == "@string/mn_vod_player_preferred"
                  for element in root.iter()), variant + ": no duplicate priority label")

    def find(identifier):
        matches = [element for element in root.iter()
                   if element.get(ANDROID + "id") == "@+id/" + identifier]
        check(len(matches) == 1, variant + ": unique " + identifier)
        return matches[0]

    live = find("llHomeLive")
    siblings = list(parents[live])
    check(len(siblings) == 2 and siblings[0] is live, variant + ": live remains in left column")
    player_row = siblings[1]
    check(player_row.tag == "LinearLayout", variant + ": Player entries replace empty right column")
    for attribute in ("layout_width", "layout_height", "layout_weight", "layout_marginStart",
                      "layout_marginLeft", "layout_marginEnd", "layout_marginRight"):
        check(player_row.get(ANDROID + attribute) == live.get(ANDROID + attribute),
              variant + ": live and Player columns align: " + attribute)
    check(live.get(ANDROID + "focusable") == "true", variant + ": live still focusable")
    for identifier in ("llMediaSetting", "llPlay"):
        check(find(identifier).get(ANDROID + "focusable") == "true",
              variant + ": existing player entry focusable: " + identifier)
    danmu = find("llDanmuOpen")
    purify = find("llVideoPurify")
    check(parents[danmu] is parents[purify], variant + ": danmu and purify share Player row")
    check(parents[danmu] is player_row, variant + ": danmu and purify sit beside live without blank row")
    check(list(player_row) == [danmu, purify], variant + ": Player row has no extra spacer")
    rows = list(parents[parents[live]])
    check(rows.index(parents[live]) == rows.index(parents[parents[find("llPlay")]]) + 1,
          variant + ": danmu row immediately follows player settings")
    check(rows.index(parents[find("llSystemSettings")]) == rows.index(parents[live]) + 1,
          variant + ": System section follows compact Player rows")
    check(list(parents[parents[danmu]])[-1] is parents[danmu], variant + ": Player row stays right")


def resources(locale):
    root = ET.parse(ROOT / "app/src/main/res" / locale / "strings.xml").getroot()
    arrays = {array.get("name"): [item.text for item in array] for array in root.findall("string-array")}
    strings = {item.get("name"): item.text for item in root.findall("string")}
    return arrays, strings


defaults, _ = resources("values")
check(defaults["media_title_tag"] == ["General", "ExoPlayer", "IjkPlayer"], "matching tab tags")
check(defaults["media_content_tag_General"] == ["VodPlayerPreferred"], "one General priority handler")
check(defaults["media_content_tag_ExoPlayer"] == ["ExoRenderer"], "EXO settings unchanged")
check(defaults["media_content_tag_IjkPlayer"] == ["IjkMediaCodecMode", "IjkCache"], "IJK settings unchanged")
for locale, title, options in (
        ("values", "General", ["Prefer data source", "Prefer global settings"]),
        ("values-zh", "通用", ["优先数据源", "优先全局设置"])):
    localized, strings = resources(locale)
    arrays = {**defaults, **localized}
    check(arrays["media_title"] == [title, "EXO", "IJK"], locale + ": General first, engines retained")
    check(arrays["media_content_General"] == ["@string/mn_vod_player_preferred"],
          locale + ": single priority entry reuses translated label")
    check(arrays["media_content_General_VodPlayerPreferred"] == options,
          locale + ": existing two values and index meanings preserved")
    check(bool(strings["mn_vod_player_preferred"]) and bool(strings["vod_player_preferred_tip"]),
          locale + ": label and saved-video explanation retained")
    for tag in arrays["media_title_tag"]:
        check(len(arrays["media_content_" + tag]) == len(arrays["media_content_tag_" + tag]),
              locale + ": matching entries for " + tag)
    check(sum(tags.count("VodPlayerPreferred") for name, tags in arrays.items()
              if name.startswith("media_content_tag_")) == 1, locale + ": unique inner priority entry")

java = ROOT / "app/src/main/java/com/github/tvbox/osc"
source = (java / "ui/dialog/MediaSettingDialog.java").read_text()
branches = re.findall(r"case VodPlayerPreferred:\s*(.*?)\s*break;", source, re.S)
check(len(branches) == 2, "priority has click and display branches")
check("HawkUtils.nextVodPlayerPreferred();" in branches[0]
      and "R.string.vod_player_preferred_tip" in branches[0], "click cycles stored choice and explains scope")
check("HawkUtils.getVodPlayerPreferredDesc(tvContent.getContext())" in branches[1], "display reads current stored choice")
check("contentAdapter.refreshNotifyItemChanged(i);" in source, "changed row refreshes immediately")
check("getListContent(listTitle.get(0).tag)" in source, "General content is shown on open")
check(bool(re.search(r"enum MediaSettingEnum\s*\{\s*VodPlayerPreferred,", source)), "priority enum wired")
fragment = (java / "ui/fragment/ModelSettingFragment.java").read_text()
check("VodPlayerPreferred" not in fragment, "Fragment has no removed view references or duplicate listener")
check("RefreshEvent.TYPE_DANMU_OPEN_CHANGED" in fragment and "tvDanmuOpen = null;" in fragment,
      "danmu notification and view lifecycle retained")
check("mediaSettingDialog.setOnDismissListener(dialog -> refreshPlaybackPreferences());" in fragment,
      "existing dialog entry and dismiss refresh retained")
hawk = (java / "util/HawkUtils.java").read_text()
check("return Hawk.get(HawkConfig.VOD_PLAYER_PREFERRED, 1);" in hawk, "default remains global settings")
check("Hawk.put(HawkConfig.VOD_PLAYER_PREFERRED, index);" in hawk, "same persisted preference key")
check("return i == 0;" in hawk, "data-source preference still index zero")
config = (java / "util/HawkConfig.java").read_text()
check('VOD_PLAYER_PREFERRED = "vod_player_preferred";' in config, "stored key unchanged")
print("Player settings: " + str(checks) + " static layout/resource/wiring checks passed")

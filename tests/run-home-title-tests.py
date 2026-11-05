#!/usr/bin/env python3
"""Offline resource/source checks; these do not replace Android UI verification."""
from itertools import product
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ANDROID = "{http://schemas.android.com/apk/res/android}"
RES = ROOT / "app/src/main/res"
JAVA = ROOT / "app/src/main/java/com/github/tvbox/osc/ui/activity/HomeActivity.java"

layout = ET.parse(RES / "layout/activity_home.xml").getroot()
parents = {child: parent for parent in layout.iter() for child in parent}
title = next(node for node in layout.iter() if node.get(ANDROID + "id") == "@+id/tvName")
frame = parents[title]
space = parents[frame]
assert title.get(ANDROID + "textColor") == "@color/color_FFFFFF"
assert not (RES / "color/font_home_title.xml").exists()
assert title.get(ANDROID + "layout_width") == "wrap_content"
assert title.get(ANDROID + "clickable") == "true"
assert title.get(ANDROID + "focusable") == "true"
assert title.get(ANDROID + "nextFocusRight") == "@id/tvWifi"
assert title.get(ANDROID + "nextFocusDown") == "@id/mGridViewCategory"
assert ANDROID + "background" not in title.attrib
assert ANDROID + "foreground" not in title.attrib
assert frame.get(ANDROID + "layout_width") == "wrap_content"
assert frame.get(ANDROID + "addStatesFromChildren") == "true"
assert frame.get(ANDROID + "background") == "@drawable/button_dialog_vod"
assert ANDROID + "foreground" not in frame.attrib
assert space.get(ANDROID + "layout_weight") == "1"
assert ANDROID + "background" not in space.attrib
icons = [node for node in layout.iter() if node.get(ANDROID + "id") in
         {"@+id/tvWifi", "@+id/tvFind", "@+id/tvStyle", "@+id/tvDrawer", "@+id/tvMenu"}]
assert len(icons) == 5
assert all(node.get(ANDROID + "background") == frame.get(ANDROID + "background") for node in icons)
print("Title frame shares all five top buttons' background; compact bounds and navigation retained")

selector = ET.parse(RES / "drawable/button_dialog_vod.xml").getroot()

def selected_shape(states):
    for item in selector:
        requirements = {
            key.removeprefix(ANDROID + "state_"): value == "true"
            for key, value in item.attrib.items()
            if key.startswith(ANDROID + "state_")
        }
        if all(states.get(key, False) == value for key, value in requirements.items()):
            return item.find("shape")
    raise AssertionError("Shared button has no matching background")

count = 0
for focused, hovered, activated, pressed, blinking in product((False, True), repeat=5):
    states = dict(focused=focused, hovered=hovered, activated=activated, pressed=pressed)
    shape = selected_shape(states)
    assert shape.find("solid").get(ANDROID + "color") == (
        "?attr/color_theme_40" if focused or pressed else "@android:color/transparent")
    stroke = shape.find("stroke")
    assert (stroke is not None) == (focused and not pressed)
    if stroke is not None:
        assert stroke.get(ANDROID + "color") == "@android:color/white"
    count += 1
print(f"{count} shared background state checks passed; no title-specific hover/color feedback")

source = JAVA.read_text()
assert "tvNameFrame" not in source
assert "tvName.setActivated" not in source
assert "tvName.setBackground" not in source
assert "stopTvNameAnimation" not in source
method = re.search(r"private void tvNameAnimation\(\)\s*\{(.*?)\n    \}", source, re.S).group(1)
expected_method = """
AlphaAnimation blinkAnimation = new AlphaAnimation(0.0f, 1.0f);
blinkAnimation.setDuration(500);
blinkAnimation.setStartOffset(20);
blinkAnimation.setRepeatMode(Animation.REVERSE);
blinkAnimation.setRepeatCount(Animation.INFINITE);
tvName.startAnimation(blinkAnimation);
"""
normalize = lambda text: re.sub(r"\s+", "", text)
assert normalize(method) == normalize(expected_method)
assert source.count("tvName.clearAnimation();") == 3
print("Original text-only animation body and three stop sites verified; frame has no animation")

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android" / "app" / "src" / "main"
LAYOUTS = [
    MAIN / "res" / "layout" / "activity_main.xml",
    MAIN / "res" / "layout-land" / "activity_main.xml",
]


class AndroidResourceTests(unittest.TestCase):
    def test_manifest_and_layouts_are_well_formed(self):
        ET.parse(MAIN / "AndroidManifest.xml")
        for layout in LAYOUTS:
            with self.subTest(layout=str(layout)):
                ET.parse(layout)

    def test_each_layout_has_unique_ids_and_all_kotlin_references(self):
        kotlin = "\n".join(path.read_text(encoding="utf-8") for path in (MAIN / "java" / "com" / "example" / "lantouchpad").glob("*.kt"))
        referenced = set(re.findall(r"R\.id\.([A-Za-z0-9_]+)", kotlin))
        for layout in LAYOUTS:
            source = layout.read_text(encoding="utf-8")
            ids = re.findall(r"\+id/([A-Za-z0-9_]+)", source)
            with self.subTest(layout=str(layout)):
                self.assertEqual(len(ids), len(set(ids)), "layout contains duplicate resource IDs")
                self.assertEqual(sorted(referenced - set(ids)), [], "Kotlin references missing from this layout")


    def test_android_220_fullscreen_and_snipping_resources_exist(self):
        for layout in LAYOUTS:
            source = layout.read_text(encoding="utf-8")
            self.assertIn("@+id/btnFullscreen", source)
            self.assertIn("@+id/btnFullscreenSnip", source)
            self.assertIn("@+id/cropOverlay", source)
        crop = MAIN / "java" / "com" / "example" / "lantouchpad" / "CropOverlayView.kt"
        self.assertTrue(crop.is_file())
        self.assertIn("cropSnapshot", (MAIN / "java" / "com" / "example" / "lantouchpad" / "ScreenView.kt").read_text(encoding="utf-8"))

    def test_activity_is_not_forced_to_landscape(self):
        manifest = ET.parse(MAIN / "AndroidManifest.xml")
        activity = next(node for node in manifest.iter() if node.tag.endswith("activity"))
        self.assertNotIn("screenOrientation", activity.attrib)
        self.assertIn("orientation", activity.attrib.get("{http://schemas.android.com/apk/res/android}configChanges", ""))


if __name__ == "__main__":
    unittest.main(verbosity=2)

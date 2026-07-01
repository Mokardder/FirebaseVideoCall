import unittest

from src.pvc_card_print_studio.core import CardRatio, cover_crop_box, parse_ratio_payload


class RatioParsingTest(unittest.TestCase):
    def test_parses_width_height_payload(self):
        self.assertEqual(parse_ratio_payload({"width": 86, "height": 54}), CardRatio(86, 54))

    def test_parses_decimal_ratio_payload(self):
        self.assertEqual(parse_ratio_payload({"ratio": 1.5}), CardRatio(1.5, 1))

    def test_parses_aspect_ratio_string_payload(self):
        self.assertEqual(parse_ratio_payload({"aspectRatio": "90:60"}), CardRatio(90, 60))

    def test_rejects_unknown_payload(self):
        with self.assertRaises(ValueError):
            parse_ratio_payload({"unexpected": True})


class CropTest(unittest.TestCase):
    def test_crops_wide_images_from_sides(self):
        self.assertEqual(cover_crop_box((2000, 1000), CardRatio(1, 1)), (500, 0, 1500, 1000))

    def test_crops_tall_images_from_top_and_bottom(self):
        self.assertEqual(cover_crop_box((1000, 2000), CardRatio(1, 1)), (0, 500, 1000, 1500))


if __name__ == "__main__":
    unittest.main()

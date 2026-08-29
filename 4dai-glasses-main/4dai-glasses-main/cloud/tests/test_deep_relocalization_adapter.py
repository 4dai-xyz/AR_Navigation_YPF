from __future__ import annotations

import unittest

from cloud.app.services.deep_relocalization_adapter import DeepRelocalizationAdapter, DeepRelocalizationUnavailable


class DeepRelocalizationAdapterTest(unittest.TestCase):
    def test_default_adapter_is_unavailable_without_configuration(self) -> None:
        adapter = DeepRelocalizationAdapter()
        availability = adapter.availability()

        self.assertFalse(availability["available"])
        self.assertIn(availability["reason"], {"missing_optional_dependencies", "not_configured"})

    def test_localize_raises_clear_unavailable_error(self) -> None:
        adapter = DeepRelocalizationAdapter()

        with self.assertRaises(DeepRelocalizationUnavailable):
            adapter.localize()


if __name__ == "__main__":
    unittest.main()

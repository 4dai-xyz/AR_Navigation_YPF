from __future__ import annotations

import re
import unittest
from pathlib import Path

from cloud.app.core.error_codes import BusinessCode, DEFAULT_MESSAGES


class OpenApiContractConsistencyTest(unittest.TestCase):
    def setUp(self) -> None:
        repo_root = Path(__file__).resolve().parents[2]
        self.openapi_path = repo_root / "contracts" / "openapi" / "indoor-navigation-api-v1.yaml"
        self.openapi_text = self.openapi_path.read_text(encoding="utf-8")

    def test_error_code_enum_matches_runtime_business_codes(self) -> None:
        match = re.search(r"enum: \[([^\]]+)\]\n\s+description: Business error code", self.openapi_text)
        self.assertIsNotNone(match)
        documented_codes = {int(item.strip()) for item in match.group(1).split(",")}
        runtime_codes = {int(code) for code in BusinessCode if code is not BusinessCode.OK}

        self.assertEqual(documented_codes, runtime_codes)

    def test_timeout_examples_use_runtime_messages_and_operation(self) -> None:
        self.assertIn(f"message: {DEFAULT_MESSAGES[BusinessCode.RELOCALIZATION_TIMEOUT]}", self.openapi_text)
        self.assertIn(f"message: {DEFAULT_MESSAGES[BusinessCode.ROUTE_PLANNING_TIMEOUT]}", self.openapi_text)
        self.assertIn("operation: visual_locate", self.openapi_text)
        self.assertIn("operation: indoor_route", self.openapi_text)

    def test_multipart_route_prior_is_documented_as_json_string(self) -> None:
        self.assertIn("description: JSON string form of RoutePrior in multipart requests.", self.openapi_text)
        self.assertIn("route_edge_ids:", self.openapi_text)

    def test_package_error_example_is_documented(self) -> None:
        self.assertIn("Error9001Package:", self.openapi_text)
        self.assertIn("error_type: venue_package_error", self.openapi_text)
        self.assertIn("validation_errors:", self.openapi_text)

    def test_500_response_covers_package_and_generic_internal_errors(self) -> None:
        self.assertNotIn("#/components/responses/PackageError", self.openapi_text)
        self.assertIn("InternalServerError:", self.openapi_text)
        self.assertIn("Error9001Package:", self.openapi_text)
        self.assertIn("Error9001Internal:", self.openapi_text)
        self.assertIn("data: null", self.openapi_text)

    def test_auth_security_matches_runtime_placeholder_scope(self) -> None:
        for operation_id in ("visualLocate", "indoorRoute", "getVenueMeta"):
            pattern = rf"operationId: {operation_id}\n\s+security:\n\s+- bearerAuth: \[\]"
            self.assertRegex(self.openapi_text, pattern)
        self.assertRegex(self.openapi_text, r"operationId: healthCheck\n\s+security: \[\]")


if __name__ == "__main__":
    unittest.main()

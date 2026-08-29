from __future__ import annotations

from enum import IntEnum


class BusinessCode(IntEnum):
    OK = 0
    INVALID_PARAMETER = 1001
    VENUE_NOT_FOUND = 1002
    FLOOR_NOT_FOUND = 1003
    TARGET_POI_NOT_FOUND = 1004
    IMAGE_PARSE_FAILED = 2001
    RELOCALIZATION_FAILED = 2002
    RELOCALIZATION_TIMEOUT = 2003
    IMAGE_QUALITY_INSUFFICIENT = 2004
    ROUTE_PLANNING_FAILED = 3001
    ROUTE_PLANNING_TIMEOUT = 3002
    AUTH_UNAUTHORIZED = 4001
    RATE_LIMITED = 4002
    INTERNAL_ERROR = 9001


DEFAULT_MESSAGES = {
    BusinessCode.OK: "ok",
    BusinessCode.INVALID_PARAMETER: "invalid parameter",
    BusinessCode.VENUE_NOT_FOUND: "venue not found",
    BusinessCode.FLOOR_NOT_FOUND: "floor not found",
    BusinessCode.TARGET_POI_NOT_FOUND: "target poi not found",
    BusinessCode.IMAGE_PARSE_FAILED: "image parse failed",
    BusinessCode.RELOCALIZATION_FAILED: "relocalization failed",
    BusinessCode.RELOCALIZATION_TIMEOUT: "relocalization timeout",
    BusinessCode.IMAGE_QUALITY_INSUFFICIENT: "image quality insufficient",
    BusinessCode.ROUTE_PLANNING_FAILED: "route planning failed",
    BusinessCode.ROUTE_PLANNING_TIMEOUT: "route planning timeout",
    BusinessCode.AUTH_UNAUTHORIZED: "unauthorized",
    BusinessCode.RATE_LIMITED: "rate limit exceeded",
    BusinessCode.INTERNAL_ERROR: "internal error",
}


class ApiError(Exception):
    def __init__(
        self,
        code: BusinessCode,
        request_id: str,
        message: str | None = None,
        details: dict | None = None,
        http_status: int = 400,
    ) -> None:
        super().__init__(message or DEFAULT_MESSAGES[code])
        self.code = code
        self.request_id = request_id
        self.message = message or DEFAULT_MESSAGES[code]
        self.details = details
        self.http_status = http_status

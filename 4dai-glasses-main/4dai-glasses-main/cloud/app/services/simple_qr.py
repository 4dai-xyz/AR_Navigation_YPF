from __future__ import annotations

import io

import qrcode
import qrcode.image.svg


def qr_svg(payload: str) -> str:
    factory = qrcode.image.svg.SvgPathImage
    image = qrcode.make(
        payload,
        image_factory=factory,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        border=4,
        box_size=10,
    )
    buffer = io.BytesIO()
    image.save(buffer)
    return buffer.getvalue().decode("utf-8")

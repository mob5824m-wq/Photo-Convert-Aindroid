from __future__ import annotations

import base64
import mimetypes
import os
import shutil
import uuid
import xml.etree.ElementTree as ET
from email.utils import formatdate
from pathlib import Path
from typing import Optional, Tuple
from urllib.parse import quote, unquote, urlparse

from fastapi import APIRouter, Request, Response
from fastapi.responses import FileResponse

from server.config import SettingsManager
from server.watcher import PipelineWorker

WEBDAV_METHODS = [
    "OPTIONS",
    "GET",
    "HEAD",
    "PUT",
    "DELETE",
    "MKCOL",
    "PROPFIND",
    "MOVE",
    "COPY",
]


def _check_auth(request: Request, settings_mgr: SettingsManager) -> bool:
    cfg = settings_mgr.get()
    if not cfg.auth_enabled or not cfg.auth_password:
        return True
    header = request.headers.get("Authorization", "")
    if not header.startswith("Basic "):
        return False
    try:
        decoded = base64.b64decode(header[6:].strip()).decode("utf-8", errors="ignore")
        user, _, pw = decoded.partition(":")
        return user == cfg.auth_username and pw == cfg.auth_password
    except Exception:
        return False


def _resolve_dav_path(
    rel_path: str, settings_mgr: SettingsManager
) -> Tuple[str, Optional[Path]]:
    inc_dir, out_dir = settings_mgr.ensure_directories()
    quar_dir = settings_mgr.quarantine_path()
    cleaned = unquote(rel_path).strip("/")
    if not cleaned:
        return "root", None
    parts = [p for p in cleaned.split("/") if p and p not in (".", "..")]
    if not parts:
        return "root", None
    share = parts[0].lower()
    sub = parts[1:]
    if share == "incoming":
        base = inc_dir
        target = (base.joinpath(*sub)).resolve() if sub else base
        return ("incoming", target) if str(target).startswith(str(base)) else ("forbidden", None)
    if share in ("output", "googlephotos_output"):
        base = out_dir
        target = (base.joinpath(*sub)).resolve() if sub else base
        return ("output", target) if str(target).startswith(str(base)) else ("forbidden", None)
    if share == "quarantine":
        base = quar_dir
        target = (base.joinpath(*sub)).resolve() if sub else base
        return ("quarantine", target) if str(target).startswith(str(base)) else ("forbidden", None)
    return "not_found", None


def _build_prop_xml(
    href: str,
    display_name: str,
    is_dir: bool,
    size_bytes: int = 0,
    mtime: float = 0.0,
) -> ET.Element:
    resp = ET.Element("{DAV:}response")
    href_el = ET.SubElement(resp, "{DAV:}href")
    href_el.text = href
    propstat = ET.SubElement(resp, "{DAV:}propstat")
    prop = ET.SubElement(propstat, "{DAV:}prop")

    dn = ET.SubElement(prop, "{DAV:}displayname")
    dn.text = display_name

    rt = ET.SubElement(prop, "{DAV:}resourcetype")
    if is_dir:
        ET.SubElement(rt, "{DAV:}collection")
    else:
        cl = ET.SubElement(prop, "{DAV:}getcontentlength")
        cl.text = str(size_bytes)
        ct = ET.SubElement(prop, "{DAV:}getcontenttype")
        guessed, _ = mimetypes.guess_type(display_name)
        ct.text = guessed or "application/octet-stream"

    if mtime > 0:
        lm = ET.SubElement(prop, "{DAV:}getlastmodified")
        lm.text = formatdate(mtime, usegmt=True)

    status = ET.SubElement(propstat, "{DAV:}status")
    status.text = "HTTP/1.1 200 OK"
    return resp


def create_webdav_router(
    settings_mgr: SettingsManager, worker: PipelineWorker
) -> APIRouter:
    router = APIRouter()

    @router.api_route("/webdav", methods=WEBDAV_METHODS)
    @router.api_route("/webdav/{rel_path:path}", methods=WEBDAV_METHODS)
    async def handle_webdav(request: Request, rel_path: str = "") -> Response:
        if not _check_auth(request, settings_mgr):
            return Response(
                status_code=401,
                headers={
                    "WWW-Authenticate": 'Basic realm="Pixel Photo NAS WebDAV"',
                    "DAV": "1, 2",
                },
            )

        method = request.method.upper()
        common_headers = {
            "DAV": "1, 2",
            "MS-Author-Via": "DAV",
            "Allow": ", ".join(WEBDAV_METHODS),
        }

        if method == "OPTIONS":
            return Response(status_code=200, headers=common_headers)

        kind, fs_path = _resolve_dav_path(rel_path, settings_mgr)
        if kind == "forbidden":
            return Response(status_code=403, headers=common_headers)

        if method == "PROPFIND":
            depth = request.headers.get("Depth", "1").strip()
            multistatus = ET.Element("{DAV:}multistatus")
            if kind == "root":
                multistatus.append(_build_prop_xml("/webdav/", "PixelPhotoNAS", True))
                if depth != "0":
                    inc_dir, out_dir = settings_mgr.ensure_directories()
                    quar_dir = settings_mgr.quarantine_path()
                    multistatus.append(
                        _build_prop_xml("/webdav/Incoming/", "Incoming", True, mtime=inc_dir.stat().st_mtime)
                    )
                    multistatus.append(
                        _build_prop_xml("/webdav/Output/", "Output", True, mtime=out_dir.stat().st_mtime)
                    )
                    multistatus.append(
                        _build_prop_xml("/webdav/Quarantine/", "Quarantine", True, mtime=quar_dir.stat().st_mtime)
                    )
            elif fs_path is not None and fs_path.exists():
                norm_href = "/webdav/" + "/".join(
                    quote(p) for p in unquote(rel_path).strip("/").split("/") if p
                )
                if fs_path.is_dir() and not norm_href.endswith("/"):
                    norm_href += "/"
                st = fs_path.stat()
                multistatus.append(
                    _build_prop_xml(
                        norm_href,
                        fs_path.name,
                        fs_path.is_dir(),
                        size_bytes=st.st_size,
                        mtime=st.st_mtime,
                    )
                )
                if fs_path.is_dir() and depth != "0":
                    for child in sorted(fs_path.iterdir()):
                        if child.name.startswith("."):
                            continue
                        cst = child.stat()
                        child_href = norm_href + quote(child.name) + (
                            "/" if child.is_dir() else ""
                        )
                        multistatus.append(
                            _build_prop_xml(
                                child_href,
                                child.name,
                                child.is_dir(),
                                size_bytes=cst.st_size,
                                mtime=cst.st_mtime,
                            )
                        )
            else:
                return Response(status_code=404, headers=common_headers)

            xml_bytes = ET.tostring(
                multistatus, encoding="utf-8", xml_declaration=True
            )
            return Response(
                content=xml_bytes,
                status_code=207,
                media_type='application/xml; charset="utf-8"',
                headers=common_headers,
            )

        if fs_path is None:
            return Response(
                status_code=405 if kind == "root" else 404, headers=common_headers
            )

        if method in ("GET", "HEAD"):
            if not fs_path.exists():
                return Response(status_code=404, headers=common_headers)
            if fs_path.is_dir():
                return Response(
                    content=f"Pixel Photo NAS WebDAV Directory: {fs_path.name}\n",
                    status_code=200,
                    media_type="text/plain",
                    headers=common_headers,
                )
            return FileResponse(path=str(fs_path), headers=common_headers)

        if method == "MKCOL":
            if fs_path.exists():
                return Response(status_code=405, headers=common_headers)
            fs_path.mkdir(parents=True, exist_ok=True)
            return Response(status_code=201, headers=common_headers)

        if method == "PUT":
            fs_path.parent.mkdir(parents=True, exist_ok=True)
            existed = fs_path.exists()
            tmp = fs_path.parent / f".dav_upload_{uuid.uuid4().hex}.tmp"
            try:
                with tmp.open("wb") as out_f:
                    async for chunk in request.stream():
                        out_f.write(chunk)
                os.replace(tmp, fs_path)
            finally:
                if tmp.exists():
                    tmp.unlink(missing_ok=True)
            if kind == "incoming":
                worker.enqueue_if_auto(fs_path)
            return Response(
                status_code=204 if existed else 201, headers=common_headers
            )

        if method == "DELETE":
            if not fs_path.exists():
                return Response(status_code=404, headers=common_headers)
            inc_dir, out_dir = settings_mgr.ensure_directories()
            quar_dir = settings_mgr.quarantine_path()
            if fs_path in (inc_dir, out_dir, quar_dir):
                return Response(status_code=403, headers=common_headers)
            if fs_path.is_dir():
                shutil.rmtree(fs_path)
            else:
                fs_path.unlink()
            return Response(status_code=204, headers=common_headers)

        if method in ("MOVE", "COPY"):
            if not fs_path.exists():
                return Response(status_code=404, headers=common_headers)
            dest_hdr = request.headers.get("Destination", "")
            parsed = urlparse(dest_hdr)
            dest_rel = parsed.path
            if dest_rel.startswith("/webdav"):
                dest_rel = dest_rel[len("/webdav") :]
            dest_kind, dest_fs = _resolve_dav_path(dest_rel, settings_mgr)
            if dest_fs is None or dest_kind in ("forbidden", "not_found", "root"):
                return Response(status_code=502, headers=common_headers)
            dest_fs.parent.mkdir(parents=True, exist_ok=True)
            existed = dest_fs.exists()
            if method == "MOVE":
                shutil.move(str(fs_path), str(dest_fs))
            else:
                shutil.copy2(str(fs_path), str(dest_fs))
            if dest_kind == "incoming" and dest_fs.is_file():
                worker.enqueue_if_auto(dest_fs)
            return Response(
                status_code=204 if existed else 201, headers=common_headers
            )

        return Response(status_code=405, headers=common_headers)

    return router

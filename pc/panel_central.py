"""
RTSP Viewer · Panel central

Servidor web para Windows que gestiona a la vez todos los equipos (TV sticks y
tablets) con la app RTSP Viewer de la red local. Solo usa la biblioteca estándar.

    python panel_central.py           → abre http://localhost:8000 en el navegador

La lista de equipos y los ajustes se guardan en panel_central.json junto a este
archivo (o junto al .exe). Los equipos no dependen de este servidor: cada uno guarda
su archivo, su texto y su modo, así que el PC se puede apagar después.
"""

import base64
import http.client
import ipaddress
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import webbrowser
import zipfile
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

APP_ID = "RTSPViewer"
DEVICE_PORT = 8080
POLL_SECONDS = 4
DEVICE_TIMEOUT = 4
SCAN_TIMEOUT = 0.8
CHUNK = 256 * 1024
SPACE_MARGIN = 50 * 1048576
MAX_SLIDESHOW_BYTES = 200 * 1048576

MODES = {"camara": "Cámara", "esquela": "Esquelas", "imagen": "Imagen"}
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp"}
VIDEO_EXT = {".mp4", ".mkv", ".webm"}
PPT_EXT = {".pptx", ".ppt", ".ppsx", ".pps"}


def base_dir():
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


CONFIG_PATH = os.path.join(base_dir(), "panel_central.json")


def mb(n):
    return f"{n / 1048576:.1f} MB"


# =========================================================================== configuración

class Config:
    def __init__(self):
        self.lock = threading.Lock()
        self.data = {"port": 8000, "seconds_per_slide": 8, "open_browser": True, "devices": []}
        try:
            # utf-8-sig: acepta el BOM que añaden algunos editores (Bloc de notas, PowerShell)
            with open(CONFIG_PATH, encoding="utf-8-sig") as f:
                self.data.update(json.load(f))
        except FileNotFoundError:
            pass
        except (OSError, ValueError) as e:
            # No perder la lista de equipos: guardar una copia del archivo dañado antes de empezar de cero.
            backup = CONFIG_PATH + ".dañado"
            try:
                shutil.copyfile(CONFIG_PATH, backup)
            except OSError:
                pass
            print(f"Aviso: no se pudo leer {CONFIG_PATH} ({e}).")
            print(f"Se ha guardado una copia en {backup} y se empieza con una configuración nueva.")
        self.save()

    def save(self):
        tmp = CONFIG_PATH + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.data, f, indent=2, ensure_ascii=False)
            os.replace(tmp, CONFIG_PATH)
        except OSError as e:
            print("No se pudo guardar la configuración:", e)

    def devices(self):
        with self.lock:
            return [dict(d) for d in self.data["devices"]]

    def add(self, ip, name=""):
        with self.lock:
            if any(d["ip"] == ip for d in self.data["devices"]):
                return False
            self.data["devices"].append({"ip": ip, "name": name})
            self.save()
            return True

    def remove(self, ip):
        with self.lock:
            self.data["devices"] = [d for d in self.data["devices"] if d["ip"] != ip]
            self.save()

    def rename(self, ip, name):
        with self.lock:
            for d in self.data["devices"]:
                if d["ip"] == ip:
                    d["name"] = name
            self.save()

    def get(self, key):
        with self.lock:
            return self.data.get(key)

    def set(self, key, value):
        with self.lock:
            self.data[key] = value
            self.save()


CFG = Config()


# =========================================================================== acceso a los equipos

class DeviceError(Exception):
    """Error con un mensaje listo para mostrar."""


def friendly(exc):
    if isinstance(exc, DeviceError):
        return str(exc)
    if isinstance(exc, socket.timeout):
        return "No responde (tiempo agotado)"
    if isinstance(exc, ConnectionRefusedError):
        return "Rechaza la conexión: ¿está abierta la app?"
    if isinstance(exc, (ConnectionResetError, BrokenPipeError, http.client.RemoteDisconnected)):
        return "Se cortó la conexión"
    if isinstance(exc, OSError):
        return f"No se puede conectar ({exc.strerror or exc})"
    return f"Error inesperado: {exc}"


def read_json(resp):
    raw = resp.read()
    try:
        data = json.loads(raw.decode("utf-8"))
    except ValueError:
        raise DeviceError(f"Respuesta no válida (HTTP {resp.status})")
    if resp.status != 200:
        raise DeviceError(data.get("error") or f"Error HTTP {resp.status}")
    return data


def dev_get(ip, path, timeout=DEVICE_TIMEOUT):
    conn = http.client.HTTPConnection(ip, DEVICE_PORT, timeout=timeout)
    try:
        conn.request("GET", path)
        return read_json(conn.getresponse())
    finally:
        conn.close()


def dev_post(ip, path, body=b""):
    if isinstance(body, str):
        body = body.encode("utf-8")
    conn = http.client.HTTPConnection(ip, DEVICE_PORT, timeout=DEVICE_TIMEOUT + 4)
    try:
        conn.request("POST", path, body=body, headers={"Content-Type": "text/plain; charset=utf-8"})
        return read_json(conn.getresponse())
    finally:
        conn.close()


def dev_upload(ip, path, name, slot, progress):
    size = os.path.getsize(path)
    conn = http.client.HTTPConnection(ip, DEVICE_PORT, timeout=60)
    try:
        conn.putrequest("POST", "/api/upload?name=" + urllib.parse.quote(name) + "&slot=" + slot)
        conn.putheader("Content-Type", "application/octet-stream")
        conn.putheader("Content-Length", str(size))
        conn.endheaders()
        sent = 0
        try:
            with open(path, "rb") as f:
                while True:
                    chunk = f.read(CHUNK)
                    if not chunk:
                        break
                    conn.send(chunk)
                    sent += len(chunk)
                    progress(sent, size)
        except (ConnectionResetError, BrokenPipeError):
            pass  # el equipo puede cortar al rechazar el archivo: leer su mensaje
        return read_json(conn.getresponse())
    finally:
        conn.close()


# =========================================================================== estado en vivo

class State:
    """Último estado conocido de cada equipo y la tarea en curso o terminada en cada uno."""

    def __init__(self):
        self.lock = threading.Lock()
        self.status = {}   # ip -> {"data": dict|None, "error": str|None, "time": float}
        self.tasks = {}    # ip -> {"text": str, "state": "running"|"ok"|"error", "progress": float|None, "time": float}
        self.notice = {"text": "", "state": "", "time": 0}
        self.busy = False  # hay una subida en curso

    def set_status(self, ip, data=None, error=None):
        with self.lock:
            self.status[ip] = {"data": data, "error": error, "time": time.time()}

    def set_task(self, ip, text, state="running", progress=None):
        with self.lock:
            self.tasks[ip] = {"text": text, "state": state, "progress": progress, "time": time.time()}

    def set_notice(self, text, state="running"):
        with self.lock:
            self.notice = {"text": text, "state": state, "time": time.time()}

    def snapshot(self):
        now = time.time()
        devices = []
        with self.lock:
            for d in CFG.devices():
                st = self.status.get(d["ip"], {})
                task = self.tasks.get(d["ip"])
                if task and task["state"] != "running" and now - task["time"] > 30:
                    task = None  # los resultados se ven 30 s
                devices.append({**d, "status": st.get("data"), "error": st.get("error"),
                                "checked": st.get("time"), "task": task})
            notice = self.notice if (self.notice["state"] == "running" or now - self.notice["time"] < 30) else None
            return {"devices": devices, "notice": notice, "busy": self.busy,
                    "seconds_per_slide": CFG.get("seconds_per_slide")}


STATE = State()


def poll_one(ip):
    try:
        data = dev_get(ip, "/api/status")
        if data.get("app") != APP_ID:
            raise DeviceError("En esa IP responde algo que no es RTSP Viewer")
        STATE.set_status(ip, data=data)
    except Exception as e:  # noqa: BLE001
        STATE.set_status(ip, error=friendly(e))


def poller():
    with ThreadPoolExecutor(max_workers=16) as ex:
        while True:
            ips = [d["ip"] for d in CFG.devices()]
            list(ex.map(poll_one, ips))
            time.sleep(POLL_SECONDS)


# =========================================================================== acciones

def run_on(ips, label, fn):
    """Ejecuta fn(ip) en paralelo en cada equipo, anotando el resultado en su tarea."""
    def one(ip):
        STATE.set_task(ip, label + "…")
        try:
            data = fn(ip)
            STATE.set_status(ip, data=data)
            STATE.set_task(ip, label + ": hecho", "ok")
        except Exception as e:  # noqa: BLE001
            STATE.set_task(ip, friendly(e), "error")

    def all_():
        with ThreadPoolExecutor(max_workers=16) as ex:
            list(ex.map(one, ips))

    threading.Thread(target=all_, daemon=True).start()


def upload_job(src_path, name, ips, seconds, workdir, slot):
    """Envía el archivo al hueco «slot» (esquela o imagen) de cada equipo y lo muestra al momento."""
    try:
        ext = os.path.splitext(name)[1].lower()
        up_path, up_name = src_path, name
        if ext in PPT_EXT:
            for ip in ips:
                STATE.set_task(ip, "Esperando la conversión del PowerPoint…")
            STATE.set_notice(f"Convirtiendo «{name}» con PowerPoint… (puede tardar unos minutos)")
            try:
                up_path, n = convert_powerpoint(src_path, seconds, workdir)
            except DeviceError as e:
                STATE.set_notice(str(e), "error")
                for ip in ips:
                    STATE.set_task(ip, "No enviado: falló la conversión", "error")
                return
            up_name = os.path.basename(up_path)
            kind = "imagen JPG" if n == 1 else f"vídeo MP4 ({n} diapositivas × {seconds} s)"
            STATE.set_notice(f"«{name}» convertido a {kind}. Enviando…")
        else:
            STATE.set_notice(f"Enviando «{name}»…")

        size = os.path.getsize(up_path)

        def one(ip):
            try:
                st = dev_get(ip, "/api/status")
                free = st.get("freeBytes", 0)
                if size + SPACE_MARGIN > free:
                    raise DeviceError(f"No cabe: ocupa {mb(size)} y quedan {mb(free)} libres")
                last = [0.0]

                def progress(sent, total):
                    p = sent / total if total else 1
                    if p - last[0] >= 0.01 or sent == total:
                        last[0] = p
                        STATE.set_task(ip, f"Enviando {int(p * 100)} %", progress=p)

                STATE.set_task(ip, "Enviando 0 %", progress=0)
                dev_upload(ip, up_path, up_name, slot, progress)
                data = dev_post(ip, "/api/mode", slot)  # enviar = mostrar al momento
                STATE.set_status(ip, data=data)
                STATE.set_task(ip, f"«{up_name}» en pantalla", "ok", 1)
                return True
            except Exception as e:  # noqa: BLE001
                STATE.set_task(ip, friendly(e), "error")
                return False

        with ThreadPoolExecutor(max_workers=8) as ex:
            ok = sum(ex.map(one, ips))
        fails = len(ips) - ok
        STATE.set_notice(f"«{up_name}» en pantalla en {ok} de {len(ips)} equipos" + (f" ({fails} con error)" if fails else ""),
                         "ok" if not fails else "error")
    finally:
        STATE.busy = False
        shutil.rmtree(workdir, ignore_errors=True)


# =========================================================================== PowerPoint

PS_SCRIPT = r"""
param([string]$In, [string]$OutDir, [int]$Seconds)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$code = 0
$app = $null
$pres = $null
try {
    try { $app = New-Object -ComObject PowerPoint.Application }
    catch { throw "No se puede abrir PowerPoint en este PC. ¿Está instalado?" }
    # ReadOnly, Untitled=false, WithWindow=false
    $pres = $app.Presentations.Open($In, -1, 0, 0)
    $n = $pres.Slides.Count
    if ($n -lt 1) { throw "La presentación no tiene diapositivas." }
    $base = [IO.Path]::GetFileNameWithoutExtension($In)
    if ($n -eq 1) {
        $w = 1920
        $h = [int][Math]::Round(1920 * $pres.PageSetup.SlideHeight / $pres.PageSetup.SlideWidth)
        $out = Join-Path $OutDir ($base + ".jpg")
        if (Test-Path $out) { Remove-Item $out -Force }
        $pres.Slides.Item(1).Export($out, "JPG", $w, $h)
    } else {
        $out = Join-Path $OutDir ($base + ".mp4")
        if (Test-Path $out) { Remove-Item $out -Force }
        # FileName, UseTimingsAndNarrations, DefaultSlideDuration, VertResolution, FramesPerSecond, Quality
        $pres.CreateVideo($out, $false, $Seconds, 1080, 30, 85)
        # 1 = en curso, 2 = en cola, 3 = terminado, 4 = fallido
        while ($pres.CreateVideoStatus -eq 1 -or $pres.CreateVideoStatus -eq 2) { Start-Sleep -Milliseconds 500 }
        if ($pres.CreateVideoStatus -ne 3) { throw "PowerPoint no pudo crear el vídeo." }
    }
    if (-not (Test-Path $out)) { throw "PowerPoint no generó el archivo." }
    Write-Output ("OK|" + $n + "|" + $out)
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    $code = 1
} finally {
    if ($pres) { try { $pres.Close() } catch {} }
    if ($app) {
        # Cerrar PowerPoint solo si no tiene otras presentaciones abiertas por el usuario.
        try { if ($app.Presentations.Count -eq 0) { $app.Quit() } } catch {}
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($app)
    }
}
exit $code
"""


def convert_powerpoint(path, seconds, workdir):
    """1 diapositiva → JPG de 1920 px; varias → MP4 1080p. Devuelve (ruta, nº de diapositivas)."""
    out_dir = os.path.join(workdir, "salida")
    os.makedirs(out_dir, exist_ok=True)
    script = os.path.join(workdir, "convertir.ps1")
    with open(script, "w", encoding="utf-8-sig") as f:
        f.write(PS_SCRIPT)
    cmd = ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script,
           "-In", os.path.abspath(path), "-OutDir", out_dir, "-Seconds", str(int(seconds))]
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=3600,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except subprocess.TimeoutExpired:
        raise DeviceError("La conversión de PowerPoint tardó demasiado (más de 1 hora).")
    except OSError as e:
        raise DeviceError(f"No se pudo ejecutar PowerShell: {e}")
    for line in r.stdout.decode("utf-8", "replace").splitlines():
        if line.startswith("OK|"):
            _, n, result = line.split("|", 2)
            return result.strip(), int(n)
    err = r.stderr.decode("utf-8", "replace").strip()
    raise DeviceError("Error al convertir el PowerPoint: " + (err.splitlines()[-1] if err else "sin detalles"))


# =========================================================================== búsqueda

def primary_ip():
    """IP de la interfaz de red principal de este PC (o None)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))  # no envía nada; solo elige la interfaz de salida
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return None


def local_networks():
    ips = [primary_ip()] if primary_ip() else []
    try:
        ips += socket.gethostbyname_ex(socket.gethostname())[2]
    except OSError:
        pass
    nets = []
    for ip in ips:
        try:
            addr = ipaddress.ip_address(ip)
        except ValueError:
            continue
        if addr.is_private and not addr.is_loopback and not addr.is_link_local:
            net = ipaddress.ip_network(f"{ip}/24", strict=False)
            if net not in nets:
                nets.append(net)
    return nets


def probe(ip):
    try:
        return str(ip) if dev_get(str(ip), "/api/status", timeout=SCAN_TIMEOUT).get("app") == APP_ID else None
    except Exception:  # noqa: BLE001
        return None


def scan_job():
    nets = local_networks()
    if not nets:
        STATE.set_notice("No se encontró ninguna red local en este PC.", "error")
        return
    STATE.set_notice("Buscando equipos en " + ", ".join(str(n) for n in nets) + "…")
    with ThreadPoolExecutor(max_workers=64) as ex:
        found = [ip for net in nets for ip in ex.map(probe, net.hosts()) if ip]
    new = [ip for ip in found if CFG.add(ip)]
    for ip in new:
        poll_one(ip)
    if not found:
        STATE.set_notice("No se encontró ningún equipo con RTSP Viewer abierto.", "error")
    else:
        STATE.set_notice(f"Encontrados {len(found)} equipos; {len(new)} nuevos añadidos a la lista.", "ok")


# =========================================================================== servidor web

def valid_ip(ip):
    try:
        return str(ipaddress.ip_address(ip.strip()))
    except (ValueError, AttributeError):
        return None


class Handler(BaseHTTPRequestHandler):
    server_version = "PanelCentral/1.0"

    def log_message(self, fmt, *args):  # sin ruido en la consola
        pass

    # ---- utilidades

    def allowed(self):
        try:
            a = ipaddress.ip_address(self.client_address[0])
        except ValueError:
            return False
        if a.is_private or a.is_loopback or a.is_link_local:
            return True
        self.send_json(403, {"error": "Solo se admiten conexiones desde la red local"})
        return False

    def send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def body_json(self, limit=1048576):
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            raise ValueError("Petición demasiado grande")
        try:
            return json.loads(self.rfile.read(n).decode("utf-8") or "{}")
        except ValueError:
            raise ValueError("JSON no válido")

    def target_ips(self, body):
        known = {d["ip"] for d in CFG.devices()}
        ips = [ip for ip in body.get("ips", []) if ip in known]
        if not ips:
            raise ValueError("Marca al menos un equipo")
        return ips

    # ---- GET

    def do_GET(self):
        if not self.allowed():
            return
        path = urllib.parse.urlparse(self.path).path
        if path in ("/", "/index.html"):
            body = PAGE.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif path == "/api/state":
            self.send_json(200, STATE.snapshot())
        else:
            self.send_json(404, {"error": "No encontrado"})

    # ---- POST

    def do_POST(self):
        if not self.allowed():
            return
        url = urllib.parse.urlparse(self.path)
        try:
            if url.path == "/api/upload":
                return self.upload(urllib.parse.parse_qs(url.query))
            body = self.body_json(MAX_SLIDESHOW_BYTES if url.path == "/api/slideshow" else 1048576)
            handler = {
                "/api/devices/add": self.add_device,
                "/api/devices/remove": self.remove_device,
                "/api/devices/rename": self.rename_device,
                "/api/scan": self.scan,
                "/api/mode": self.mode,
                "/api/text": self.text,
                "/api/delete": self.delete,
                "/api/settings": self.settings,
                "/api/slideshow": self.slideshow,
            }.get(url.path)
            if not handler:
                return self.send_json(404, {"error": "No encontrado"})
            handler(body)
        except ValueError as e:
            self.send_json(400, {"error": str(e)})

    def add_device(self, body):
        ip = valid_ip(body.get("ip", ""))
        if not ip:
            raise ValueError("IP no válida")
        name = str(body.get("name", "")).strip()[:60]
        if not CFG.add(ip, name):
            raise ValueError(f"{ip} ya está en la lista")
        threading.Thread(target=poll_one, args=(ip,), daemon=True).start()
        self.send_json(200, {"ok": True})

    def remove_device(self, body):
        CFG.remove(body.get("ip", ""))
        self.send_json(200, {"ok": True})

    def rename_device(self, body):
        CFG.rename(body.get("ip", ""), str(body.get("name", "")).strip()[:60])
        self.send_json(200, {"ok": True})

    def scan(self, _):
        threading.Thread(target=scan_job, daemon=True).start()
        self.send_json(200, {"ok": True})

    def mode(self, body):
        mode = body.get("mode")
        if mode not in MODES:
            raise ValueError("Modo no válido")
        label = "Mostrar " + MODES[mode].lower()
        run_on(self.target_ips(body), label, lambda ip: dev_post(ip, "/api/mode", mode))
        self.send_json(200, {"ok": True})

    def text(self, body):
        text = str(body.get("text", "")).strip()
        if len(text) > 500:
            raise ValueError("Texto demasiado largo (máximo 500 caracteres)")
        run_on(self.target_ips(body), "Guardar texto", lambda ip: dev_post(ip, "/api/text", text))
        self.send_json(200, {"ok": True})

    def delete(self, body):
        slot = body.get("slot")
        if slot not in ("esquela", "imagen"):
            raise ValueError("Indica qué borrar: esquela o imagen")
        # Si se estaba viendo, el equipo vuelve solo a la cámara.
        label = "Borrar " + ("esquelas" if slot == "esquela" else "imagen")
        run_on(self.target_ips(body), label, lambda ip: dev_post(ip, "/api/delete?slot=" + slot))
        self.send_json(200, {"ok": True})

    def slideshow(self, body):
        """Diapositivas dibujadas en el navegador (JPEG en base64) → ZIP → equipos marcados."""
        ips = self.target_ips(body)
        slides = body.get("slides") or []
        if not slides:
            raise ValueError("Añade al menos una diapositiva")
        if len(slides) > 100:
            raise ValueError("Demasiadas diapositivas (máximo 100)")
        try:
            secs = int(body.get("seconds") or CFG.get("seconds_per_slide") or 8)
        except (TypeError, ValueError):
            raise ValueError("Los segundos por diapositiva deben ser un número")
        if not 1 <= secs <= 600:
            raise ValueError("Los segundos por diapositiva deben estar entre 1 y 600")
        name = "".join(ch for ch in str(body.get("name") or "") if ch.isalnum() or ch in " -_.").strip(" .")[:60]
        name = (name or "Diapositivas") + ".zip"
        if STATE.busy:
            raise ValueError("Ya hay un envío en curso; espera a que termine")

        workdir = tempfile.mkdtemp(prefix="panel_central_")
        zip_path = os.path.join(workdir, name)
        try:
            with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_STORED) as z:
                for i, data_url in enumerate(slides, 1):
                    head, _, data = str(data_url).partition(",")
                    if head != "data:image/jpeg;base64":
                        raise ValueError(f"La diapositiva {i} no es una imagen JPEG válida")
                    z.writestr(f"{i:03d}.jpg", base64.b64decode(data, validate=True))
                z.writestr("config.json", json.dumps({"seconds": secs}))
        except (ValueError, base64.binascii.Error) as e:
            shutil.rmtree(workdir, ignore_errors=True)
            raise ValueError(str(e) or "Diapositivas no válidas")

        CFG.set("seconds_per_slide", secs)
        STATE.busy = True
        threading.Thread(target=upload_job, args=(zip_path, name, ips, secs, workdir, "esquela"), daemon=True).start()
        self.send_json(200, {"ok": True})

    def settings(self, body):
        try:
            secs = int(body.get("seconds_per_slide"))
        except (TypeError, ValueError):
            raise ValueError("Los segundos por diapositiva deben ser un número")
        if not 1 <= secs <= 600:
            raise ValueError("Los segundos por diapositiva deben estar entre 1 y 600")
        CFG.set("seconds_per_slide", secs)
        self.send_json(200, {"ok": True})

    def upload(self, query):
        name = os.path.basename((query.get("name") or [""])[0].replace("\\", "/")).strip()
        ext = os.path.splitext(name)[1].lower()
        length = int(self.headers.get("Content-Length") or 0)
        known = {d["ip"] for d in CFG.devices()}
        ips = [ip for ip in (query.get("ips") or [""])[0].split(",") if ip in known]
        error = None
        if not name or ext not in IMAGE_EXT | VIDEO_EXT | PPT_EXT:
            error = "Formato no admitido. Usa JPG, PNG, WEBP, MP4, MKV, WEBM o PowerPoint"
        elif length <= 0:
            error = "El archivo está vacío"
        elif not ips:
            error = "Marca al menos un equipo"
        elif STATE.busy:
            error = "Ya hay un envío en curso; espera a que termine"
        if error:
            # Leer y descartar el cuerpo: si se cierra con datos sin leer, el navegador pierde la respuesta.
            left = length
            while left > 0:
                chunk = self.rfile.read(min(CHUNK, left))
                if not chunk:
                    break
                left -= len(chunk)
            return self.send_json(400, {"error": error})

        STATE.busy = True
        workdir = tempfile.mkdtemp(prefix="panel_central_")
        src = os.path.join(workdir, name)
        try:
            received = 0
            with open(src, "wb") as f:
                while received < length:
                    chunk = self.rfile.read(min(CHUNK, length - received))
                    if not chunk:
                        break
                    f.write(chunk)
                    received += len(chunk)
            if received != length:
                raise OSError("subida incompleta")
        except OSError as e:
            STATE.busy = False
            shutil.rmtree(workdir, ignore_errors=True)
            return self.send_json(400, {"error": f"No se recibió el archivo completo ({e})"})

        seconds = int(CFG.get("seconds_per_slide") or 8)
        threading.Thread(target=upload_job, args=(src, name, ips, seconds, workdir, "imagen"), daemon=True).start()
        self.send_json(200, {"ok": True})


# =========================================================================== página

PAGE = r"""<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>RTSP Viewer · Panel central</title>
<style>
  :root { --bg:#f4f5f7; --card:#fff; --fg:#1d2330; --muted:#677084; --line:#dde1e8; --accent:#0277bd;
          --ok:#2e7d32; --bad:#c62828; --warn:#b26a00; --soft:#eef2f7; }
  @media (prefers-color-scheme: dark) { :root { --bg:#14171c; --card:#1e232b; --fg:#e8ebf0; --muted:#98a1b2;
          --line:#323946; --accent:#4fb3f6; --ok:#66bb6a; --bad:#ef5350; --warn:#ffb74d; --soft:#262c36; } }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--fg); font:14px/1.45 system-ui, -apple-system, "Segoe UI", sans-serif; }
  main { max-width:1180px; margin:0 auto; padding:20px 16px 48px; }
  h1 { font-size:22px; margin:0 0 4px; }
  .sub { color:var(--muted); margin:0 0 16px; }
  section { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:14px 16px; margin-bottom:14px; }
  h2 { font-size:12px; text-transform:uppercase; letter-spacing:.05em; color:var(--muted); margin:0 0 10px; }
  .row { display:flex; flex-wrap:wrap; gap:8px; align-items:center; }
  input[type=text], input[type=number], textarea { font:inherit; color:inherit; background:transparent;
          border:1px solid var(--line); border-radius:6px; padding:7px 9px; }
  textarea { width:100%; min-height:60px; resize:vertical; }
  button { font:inherit; border:1px solid transparent; border-radius:6px; padding:7px 13px; background:var(--accent);
          color:#fff; cursor:pointer; white-space:nowrap; }
  button.ghost { background:transparent; color:var(--fg); border-color:var(--line); }
  button.danger { background:transparent; color:var(--bad); border-color:var(--line); }
  button:disabled { opacity:.45; cursor:default; }
  .table-wrap { overflow-x:auto; }
  table { width:100%; border-collapse:collapse; min-width:880px; }
  th, td { text-align:left; padding:8px 8px; border-bottom:1px solid var(--line); vertical-align:middle; }
  th { font-size:12px; color:var(--muted); font-weight:600; }
  tr.sel td { background:var(--soft); }
  td.name { cursor:pointer; font-weight:600; } td.name:hover { text-decoration:underline dotted; }
  .ip { color:var(--muted); font-variant-numeric:tabular-nums; }
  .dot { display:inline-block; width:9px; height:9px; border-radius:50%; margin-right:6px; vertical-align:baseline; }
  .ok { color:var(--ok); } .bad { color:var(--bad); } .warn { color:var(--warn); } .muted { color:var(--muted); }
  .task { font-size:13px; } .bar { height:5px; background:var(--line); border-radius:3px; margin-top:4px; overflow:hidden; }
  .bar i { display:block; height:100%; background:var(--accent); }
  .x { background:transparent; color:var(--muted); border:0; padding:2px 6px; font-size:16px; }
  #notice { min-height:1.4em; margin:0 0 12px; }
  .grid { display:grid; grid-template-columns:1fr 1fr; gap:14px; }
  @media (max-width:820px) { .grid { grid-template-columns:1fr; } }
  .grid section { margin:0; }
  .hint { color:var(--muted); font-size:13px; margin:6px 0 0; }
  progress { width:100%; height:8px; }
  /* Editor de diapositivas */
  .ed { display:grid; grid-template-columns:220px 1fr; gap:16px; }
  @media (max-width:820px) { .ed { grid-template-columns:1fr; } }
  .slides { display:flex; flex-direction:column; gap:8px; }
  .thumb { border:2px solid var(--line); border-radius:6px; padding:4px; cursor:pointer; background:var(--soft); }
  .thumb.on { border-color:var(--accent); }
  .thumb canvas { width:100%; display:block; border-radius:3px; background:#000; }
  .thumb .tb { display:flex; justify-content:space-between; align-items:center; font-size:12px; color:var(--muted); margin-top:3px; }
  .thumb .tb button { padding:0 6px; font-size:13px; }
  .form { display:grid; grid-template-columns:150px 1fr; gap:8px 12px; align-items:center; margin:12px 0; }
  .form label { color:var(--muted); }
  .form input[type=text], .form textarea, .form select { width:100%; font:inherit; color:inherit; background:var(--card);
          border:1px solid var(--line); border-radius:6px; padding:7px 9px; }
  #preview { width:100%; aspect-ratio:16/9; display:block; border:1px solid var(--line); border-radius:6px; background:#000; }
  .chk { display:flex; gap:16px; flex-wrap:wrap; }
</style>
</head>
<body>
<main>
  <h1>RTSP Viewer · Panel central</h1>
  <p class="sub">Gestiona a la vez todos los equipos de la red. Los cambios se aplican a los equipos <b>marcados</b>.</p>
  <p id="notice"></p>

  <section>
    <h2>Equipos</h2>
    <div class="row" style="margin-bottom:10px">
      <button id="scan" class="ghost">Buscar en la red</button>
      <input type="text" id="new-ip" placeholder="IP (ej. 192.168.1.50)" size="18">
      <input type="text" id="new-name" placeholder="Nombre (opcional)" size="20">
      <button id="add" class="ghost">Añadir</button>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr>
          <th style="width:28px"><input type="checkbox" id="all" title="Marcar todos"></th>
          <th>Equipo</th><th>Conexión</th><th>Cámara</th><th>Mostrando</th><th>Esquelas</th><th>Imagen</th>
          <th>Libre</th><th style="width:210px">Última acción</th><th style="width:30px"></th>
        </tr></thead>
        <tbody id="rows"></tbody>
      </table>
    </div>
    <p class="hint">Haz clic en el nombre de un equipo para cambiarlo.</p>
  </section>

  <div class="grid">
    <section>
      <h2>Mostrar</h2>
      <div class="row">
        <button data-mode="camara">Cámara</button>
        <button data-mode="esquela">Esquelas</button>
        <button data-mode="imagen">Imagen</button>
      </div>
      <p class="hint">Cambia lo que se ve en los equipos marcados. <b>Esquelas</b> envía lo que hay en el editor de abajo
        y lo muestra al momento. <b>Imagen</b> muestra la imagen que ya tiene cada equipo (para enviar otra, usa «Enviar y mostrar»).</p>
    </section>

    <section>
      <h2>Imagen</h2>
      <div class="row">
        <input type="file" id="file" accept=".jpg,.jpeg,.png,.webp,.mp4,.mkv,.webm,.pptx,.ppt,.ppsx,.pps">
        <button id="send-file">Enviar y mostrar</button>
        <button class="danger" data-delete="imagen">Borrar imagen</button>
      </div>
      <progress id="prog" max="100" value="0" hidden></progress>
      <p class="hint">Imagen, vídeo o PowerPoint. PowerPoint con 1 diapositiva → imagen; con varias → vídeo de
        <input type="number" id="secs" min="1" max="600" style="width:64px"> s por diapositiva.</p>
    </section>
  </div>

  <section style="margin-top:14px">
    <h2>Esquelas</h2>
    <p class="hint" style="margin:0 0 12px">Prepara aquí las esquelas y pulsa <b>Mostrar → Esquelas</b> para enviarlas y mostrarlas
      en los equipos marcados. Pasan en bucle, todas con el mismo tiempo (<input type="number" id="slide-secs" min="1" max="600" style="width:64px"> s cada una).</p>
    <div class="ed">
      <div>
        <div class="row" style="margin-bottom:8px">
          <button id="add-esq" class="ghost">+ Esquela</button>
          <button id="add-img" class="ghost">+ Imagen</button>
        </div>
        <div class="slides" id="slides"></div>
      </div>
      <div>
        <div id="slide-form"></div>
        <canvas id="preview" width="1920" height="1080"></canvas>
        <div class="row" style="margin-top:10px">
          <button class="danger" data-delete="esquela">Borrar esquelas de los equipos</button>
          <button id="example-slides" class="ghost">Cargar ejemplo</button>
          <button id="clear-slides" class="ghost">Vaciar el editor</button>
        </div>
        <progress id="slides-prog" max="100" value="0" hidden></progress>
      </div>
    </div>
  </section>

  <section style="margin-top:14px">
    <h2>Texto si no hay esquelas o imagen</h2>
    <textarea id="text" maxlength="500" placeholder="Cámara no disponible"></textarea>
    <div class="row" style="margin-top:8px"><button id="send-text" class="ghost">Guardar texto</button></div>
    <p class="hint">Se ve si eliges «Esquelas» o «Imagen» en un equipo que no tiene ese contenido.</p>
  </section>
</main>

<script>
const $ = id => document.getElementById(id);
const selected = new Set(JSON.parse(localStorage.getItem('sel') || '[]'));
let state = { devices: [] }, uploading = false, secsTouched = false;

const CAM = { ok:['Funciona','ok'], fallo:['Fallo','bad'], conectando:['Conectando…','warn'], parada:['Desconectada','muted'] };
const SHOW = { camara:'Cámara', esquela:'Esquelas', imagen:'Imagen', ninguno:'App cerrada' };
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const mb = b => { const m = b / 1048576; return (m < 10 ? m.toFixed(1) : m.toFixed(0)) + ' MB'; };
function saveSel() { try { localStorage.setItem('sel', JSON.stringify([...selected])); } catch (_) {} }

function notice(text, kind) { $('notice').textContent = text || ''; $('notice').className = kind || ''; }

async function post(url, body) {
  const r = await fetch(url, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body || {}) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || ('Error ' + r.status));
  return j;
}

function render() {
  const known = new Set(state.devices.map(d => d.ip));
  for (const ip of [...selected]) if (!known.has(ip)) selected.delete(ip);
  const rows = state.devices.map(d => {
    const s = d.status, on = !!s && !d.error;
    const conn = d.checked == null ? '<span class="muted">Comprobando…</span>'
      : on ? '<span class="ok"><span class="dot" style="background:currentColor"></span>Conectado</span>'
           : `<span class="bad" title="${esc(d.error)}"><span class="dot" style="background:currentColor"></span>${esc(d.error)}</span>`;
    const cam = on ? (CAM[s.camera] || [s.camera, '']) : ['–', 'muted'];
    const kind = f => f.type === 'diapositivas' ? `${f.slides} diapositivas × ${f.seconds} s` : f.type;
    const slot = k => !on ? '<span class="muted">–</span>'
      : (s.files && s.files[k]) ? `${esc(s.files[k].name)} <div class="muted">${kind(s.files[k])}, ${mb(s.files[k].size)}</div>`
      : '<span class="muted">Nada</span>';
    const t = d.task;
    const task = !t ? '' : `<div class="task ${t.state === 'error' ? 'bad' : t.state === 'ok' ? 'ok' : ''}">${esc(t.text)}</div>`
      + (t.state === 'running' && t.progress != null ? `<div class="bar"><i style="width:${(t.progress * 100).toFixed(0)}%"></i></div>` : '');
    const sel = selected.has(d.ip);
    return `<tr class="${sel ? 'sel' : ''}">
      <td><input type="checkbox" data-ip="${esc(d.ip)}" aria-label="Marcar ${esc(d.name || d.ip)}" ${sel ? 'checked' : ''}></td>
      <td class="name" data-rename="${esc(d.ip)}">${esc(d.name || d.ip)}<div class="ip">${d.name ? esc(d.ip) : ''}</div></td>
      <td>${conn}</td>
      <td class="${cam[1]}">${esc(cam[0])}</td>
      <td>${on ? `<b>${esc(SHOW[s.showing] || s.showing)}</b>` : '<span class="muted">–</span>'}</td>
      <td>${slot('esquela')}</td>
      <td>${slot('imagen')}</td>
      <td class="muted">${on ? mb(s.freeBytes) : '–'}</td>
      <td>${task}</td>
      <td><button class="x" data-remove="${esc(d.ip)}" title="Quitar de la lista">×</button></td>
    </tr>`;
  });
  $('rows').innerHTML = rows.join('') ||
    '<tr><td colspan="10" class="muted" style="padding:18px 8px">No hay equipos. Pulsa «Buscar en la red» o añade una IP.</td></tr>';
  $('all').checked = state.devices.length > 0 && state.devices.every(d => selected.has(d.ip));
  if (!secsTouched && state.seconds_per_slide) $('secs').value = state.seconds_per_slide;
  if (typeof slideSecsTouched !== 'undefined' && !slideSecsTouched && state.seconds_per_slide) $('slide-secs').value = state.seconds_per_slide;
  if (state.notice && !uploading) notice(state.notice.text, state.notice.state === 'error' ? 'bad' : state.notice.state === 'ok' ? 'ok' : 'warn');
  $('send-file').disabled = uploading || state.busy;
  const esqBtn = document.querySelector('[data-mode=esquela]');
  if (esqBtn && !sendingSlides) esqBtn.disabled = state.busy;
}

async function refresh() {
  try { state = await (await fetch('/api/state')).json(); render(); }
  catch (e) { notice('Se perdió la conexión con el panel central. ¿Sigue abierto el programa en el PC?', 'bad'); }
}

function ips() {
  const list = state.devices.map(d => d.ip).filter(ip => selected.has(ip));
  if (!list.length) { notice('Marca al menos un equipo en la lista.', 'bad'); return null; }
  return list;
}

async function action(fn) { try { await fn(); await refresh(); } catch (e) { notice(e.message, 'bad'); } }

$('rows').addEventListener('change', e => {
  const ip = e.target.dataset.ip; if (!ip) return;
  e.target.checked ? selected.add(ip) : selected.delete(ip); saveSel(); render();
});
$('rows').addEventListener('click', e => {
  const rm = e.target.dataset.remove, rn = e.target.closest('[data-rename]')?.dataset.rename;
  if (rm) {
    if (confirm(`¿Quitar ${rm} de la lista? (No se cambia nada en el equipo)`))
      action(() => post('/api/devices/remove', { ip: rm }));
  } else if (rn) {
    const d = state.devices.find(x => x.ip === rn);
    const name = prompt(`Nombre para ${rn}:`, d?.name || '');
    if (name !== null) action(() => post('/api/devices/rename', { ip: rn, name }));
  }
});
$('all').onchange = () => { state.devices.forEach(d => $('all').checked ? selected.add(d.ip) : selected.delete(d.ip)); saveSel(); render(); };

$('scan').onclick = () => action(() => post('/api/scan'));
$('add').onclick = () => action(async () => {
  await post('/api/devices/add', { ip: $('new-ip').value, name: $('new-name').value });
  selected.add($('new-ip').value.trim()); saveSel();
  $('new-ip').value = ''; $('new-name').value = ''; notice('Equipo añadido.', 'ok');
});

document.querySelectorAll('[data-mode]').forEach(b => b.onclick = () => {
  const list = ips(); if (!list) return;
  if (b.dataset.mode === 'esquela') return sendSlides(list);  // envía el editor y lo muestra
  action(() => post('/api/mode', { ips: list, mode: b.dataset.mode }));
});
$('send-text').onclick = () => { const list = ips(); if (list) action(() => post('/api/text', { ips: list, text: $('text').value })); };
document.querySelectorAll('[data-delete]').forEach(b => b.onclick = () => {
  const list = ips(); if (!list) return;
  const what = b.dataset.delete === 'esquela' ? 'las esquelas' : 'la imagen';
  if (confirm(`¿Borrar ${what} de ${list.length} equipo(s)? Si se estaba viendo, volverán a la cámara.`))
    action(() => post('/api/delete', { ips: list, slot: b.dataset.delete }));
});
$('secs').oninput = () => { secsTouched = true; };
$('secs').onchange = () => action(async () => { await post('/api/settings', { seconds_per_slide: $('secs').value }); secsTouched = false; });

$('send-file').onclick = () => {
  const list = ips(); if (!list) return;
  const f = $('file').files[0];
  if (!f) { notice('Elige primero un archivo.', 'bad'); return; }
  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/api/upload?name=' + encodeURIComponent(f.name) + '&ips=' + encodeURIComponent(list.join(',')));
  uploading = true; $('prog').hidden = false; $('prog').value = 0; render();
  notice(`Subiendo «${f.name}» al panel central…`, 'warn');
  xhr.upload.onprogress = e => { if (e.lengthComputable) $('prog').value = 100 * e.loaded / e.total; };
  const end = () => { uploading = false; $('prog').hidden = true; refresh(); };
  xhr.onload = () => {
    let j = {}; try { j = JSON.parse(xhr.responseText); } catch (_) {}
    if (xhr.status !== 200) notice(j.error || ('Error ' + xhr.status), 'bad');
    end();
  };
  xhr.onerror = () => { notice('Se cortó la conexión al subir el archivo.', 'bad'); end(); };
  xhr.send(f);
};


// =================================================================== editor de diapositivas
const W = 1920, H = 1080;
// La esquela es siempre de fondo blanco.
const ST = { bg:'#ffffff', fg:'#111111', soft:'#444444', frame:'#111111' };
let slides = [], current = 0;
const imgCache = new Map();   // dataURL -> HTMLImageElement cargada

function newEsquela() {
  return { type:'esquela', heading:'', name:'', age:'', dep:true, cross:true, details:'', photo:null, photoPos:'debajo' };
}
function newImage() { return { type:'imagen', image:null, fit:'contain' }; }


// Datos inventados al azar para rellenar una esquela de ejemplo.
const pick = a => a[Math.floor(Math.random() * a.length)];
const RND = {
  mujer: ['Carmen','Dolores','Josefa','Rosario','Pilar','Mercedes','Concepción','Teresa','Ángeles','Manuela','Esperanza','Remedios'],
  hombre: ['José','Manuel','Antonio','Francisco','Jesús','Ramón','Andrés','Emilio','Julián','Rafael','Vicente','Ángel'],
  apellido: ['García','Fernández','López','Martínez','Rodríguez','Sánchez','Pérez','Gómez','Díaz','Vázquez','Castro','Otero','Iglesias','Rey','Varela','Domínguez'],
  santo: ['San Martín','Santa María','San Pedro','Santiago','San Juan','Santa Eulalia','San Miguel','San Esteban'],
  hora: ['10:30','11:00','12:00','16:00','16:30','17:00','17:30','18:00'],
  dia: ['lunes','martes','miércoles','jueves','viernes','sábado','domingo'],
  mes: ['enero','febrero','marzo','abril','mayo','junio','julio','agosto','septiembre','octubre','noviembre','diciembre'],
};
function randomEsquela(s) {
  const woman = Math.random() < 0.5;
  const first = pick(woman ? RND.mujer : RND.hombre);
  let a1 = pick(RND.apellido), a2 = pick(RND.apellido);
  while (a2 === a1) a2 = pick(RND.apellido);
  const santo = pick(RND.santo);
  const dia = pick(RND.dia);
  const fallecio = woman ? 'confortada' : 'confortado';
  return { ...s,
    heading: woman ? 'La Señora' : 'El Señor',
    name: `${woman ? 'Dña.' : 'D.'} ${first} ${a1} ${a2}`,
    age: String(60 + Math.floor(Math.random() * 40)),
    details: [
      `Falleció el día ${1 + Math.floor(Math.random() * 28)} de ${pick(RND.mes)} de 2026,`,
      `${fallecio} con los Santos Sacramentos.`,
      '',
      'Conducción del cadáver:',
      `${dia[0].toUpperCase()}${dia.slice(1)}, a las ${pick(RND.hora)} h, desde el tanatorio`,
      `a la iglesia parroquial de ${santo}, donde se celebrará el funeral.`,
    ].join(String.fromCharCode(10)),
  };
}

// Esquela de ejemplo con datos inventados, para ver cómo queda y usarla de plantilla.
function exampleEsquela() {
  return { ...newEsquela(), heading:'La Señora', name:'Dña. Nombre Apellido Apellido', age:'87',
    details: [
      'Falleció el día 18 de noviembre de 2026,',
      'confortada con los Santos Sacramentos.',
      '',
      'Conducción del cadáver:',
      'Viernes, a las 15:30 h, desde el tanatorio',
      'a la iglesia parroquial, donde se celebrará el funeral.',
    ].join(String.fromCharCode(10)) };
}

function loadImg(src) {
  if (!src) return Promise.resolve(null);
  if (imgCache.has(src)) return Promise.resolve(imgCache.get(src));
  return new Promise(res => {
    const im = new Image();
    im.onload = () => { imgCache.set(src, im); res(im); };
    im.onerror = () => res(null);
    im.src = src;
  });
}

// Reduce la imagen elegida a 1920 px como máximo para no cargar la página ni la red.
function readImage(file) {
  return new Promise((res, rej) => {
    const fr = new FileReader();
    fr.onerror = () => rej(new Error('No se pudo leer la imagen'));
    fr.onload = async () => {
      const im = await loadImg(fr.result);
      if (!im) return rej(new Error('El archivo no es una imagen válida'));
      const k = Math.min(1, 1920 / Math.max(im.width, im.height));
      const c = document.createElement('canvas');
      c.width = Math.round(im.width * k); c.height = Math.round(im.height * k);
      c.getContext('2d').drawImage(im, 0, 0, c.width, c.height);
      res(c.toDataURL('image/jpeg', 0.9));
    };
    fr.readAsDataURL(file);
  });
}

const SERIF = 'Georgia, "Times New Roman", serif';
function font(ctx, size, weight, style) { ctx.font = `${style || ''} ${weight || 'normal'} ${Math.round(size)}px ${SERIF}`; }

function wrap(ctx, text, maxW) {
  const out = [];
  for (const para of String(text).split('\n')) {
    const words = para.split(/\s+/).filter(Boolean);
    if (!words.length) { out.push(''); continue; }
    let line = words[0];
    for (const w of words.slice(1)) {
      if (ctx.measureText(line + ' ' + w).width <= maxW) line += ' ' + w; else { out.push(line); line = w; }
    }
    out.push(line);
  }
  return out;
}

function drawCross(ctx, cx, top, h, color) {
  const w = h * 0.62, t = h * 0.12;
  ctx.fillStyle = color;
  ctx.fillRect(cx - t / 2, top, t, h);
  ctx.fillRect(cx - w / 2, top + h * 0.26, w, t);
}

// Bloques de texto de la esquela con un factor de escala k; devuelve la altura total.
function esquelaBlocks(ctx, s, k, maxW) {
  const b = [];
  const add = (text, size, weight, style, gap, color) => {
    font(ctx, size * k, weight, style);
    for (const line of wrap(ctx, text, maxW)) b.push({ text: line, size: size * k, weight, style, color });
    b.push({ gap: gap * k });
  };
  if (s.heading.trim()) add(s.heading.trim(), 58, 'normal', 'italic', 18, 'soft');
  add(s.name.trim() || 'Nombre', 104, 'bold', '', 14, 'fg');
  if (String(s.age).trim()) add(/^\d+$/.test(String(s.age).trim()) ? `${String(s.age).trim()} años` : String(s.age).trim(), 52, 'normal', 'italic', 10, 'soft');
  if (s.dep) add('D.E.P.', 64, 'bold', '', 20, 'fg');
  if (s.details.trim()) {
    b.push({ rule: true, gap: 34 * k });
    for (const line of s.details.trim().split('\n')) {
      const strong = /:\s*$/.test(line);
      add(line.trim(), 46, strong ? 'bold' : 'normal', '', strong ? 4 : 12, strong ? 'fg' : 'soft');
    }
  }
  let h = 0;
  for (const x of b) h += x.gap != null ? x.gap : x.size * 1.22;
  return { blocks: b, height: h };
}

async function drawSlide(ctx, s) {
  ctx.save();
  ctx.clearRect(0, 0, W, H);
  if (s.type === 'imagen') {
    ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);
    const im = await loadImg(s.image);
    if (im) {
      const k = (s.fit === 'cover' ? Math.max : Math.min)(W / im.width, H / im.height);
      const w = im.width * k, h = im.height * k;
      ctx.drawImage(im, (W - w) / 2, (H - h) / 2, w, h);
    } else {
      ctx.fillStyle = '#888'; font(ctx, 56); ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.fillText('Elige una imagen', W / 2, H / 2);
    }
    ctx.restore();
    return;
  }

  const st = ST;
  const col = c => c === 'fg' ? st.fg : st.soft;
  ctx.fillStyle = st.bg; ctx.fillRect(0, 0, W, H);
  ctx.strokeStyle = st.frame;
  ctx.lineWidth = 10; ctx.strokeRect(36, 36, W - 72, H - 72);
  ctx.lineWidth = 3;  ctx.strokeRect(58, 58, W - 116, H - 116);

  // Imagen recortada para llenar el recuadro, con un marco fino
  const framed = (im, x, y, w, h) => {
    ctx.save();
    const k = Math.max(w / im.width, h / im.height);
    ctx.beginPath(); ctx.rect(x, y, w, h); ctx.clip();
    ctx.drawImage(im, x + (w - im.width * k) / 2, y + (h - im.height * k) / 2, im.width * k, im.height * k);
    ctx.restore();
    ctx.strokeStyle = st.frame; ctx.lineWidth = 3; ctx.strokeRect(x - 8, y - 8, w + 16, h + 16);
  };

  const photo = await loadImg(s.photo);
  let x0 = 130, x1 = W - 130, bottom = H - 110;
  if (photo && s.photoPos === 'izquierda') {
    // Foto de la persona, en vertical a la izquierda
    const ph = 640, pw = Math.min(520, ph * photo.width / photo.height);
    framed(photo, 170, (H - ph) / 2, pw, ph);
    x0 = 170 + pw + 80;
  } else if (photo) {
    // Imagen de un servicio, en una franja alargada debajo del texto
    const sh = 250, sy = H - 96 - sh;
    framed(photo, 150, sy, W - 300, sh);
    bottom = sy - 28;
  }
  const cx = (x0 + x1) / 2, maxW = x1 - x0;

  let top = 110;
  const strip = photo && s.photoPos !== 'izquierda';
  if (s.cross) { drawCross(ctx, cx, strip ? 84 : 96, strip ? 90 : 120, st.fg); top = strip ? 196 : 250; }
  const avail = bottom - top;

  // Reducir el tamaño hasta que todo quepa
  let k = 1, lay = esquelaBlocks(ctx, s, k, maxW);
  while (lay.height > avail && k > 0.35) { k -= 0.04; lay = esquelaBlocks(ctx, s, k, maxW); }

  let y = top + Math.max(0, (avail - lay.height) / 2);
  ctx.textAlign = 'center'; ctx.textBaseline = 'top';
  for (const x of lay.blocks) {
    if (x.rule) {
      ctx.strokeStyle = st.soft; ctx.lineWidth = 2;
      ctx.beginPath(); ctx.moveTo(cx - maxW * 0.18, y + x.gap / 2); ctx.lineTo(cx + maxW * 0.18, y + x.gap / 2); ctx.stroke();
      y += x.gap;
    } else if (x.gap != null) {
      y += x.gap;
    } else {
      font(ctx, x.size, x.weight, x.style); ctx.fillStyle = col(x.color);
      ctx.fillText(x.text, cx, y);
      y += x.size * 1.22;
    }
  }
  ctx.restore();
}

// ---- estado del editor (se guarda en este navegador)
function saveSlides() {
  try { localStorage.setItem('slides', JSON.stringify(slides)); }
  catch (_) {
    try { localStorage.setItem('slides', JSON.stringify(slides.map(s => ({ ...s, photo: null, image: null })))); } catch (_) {}
  }
}
try { slides = JSON.parse(localStorage.getItem('slides') || '[]'); } catch (_) { slides = []; }
if (!slides.length) slides = [exampleEsquela()];

let drawToken = 0;
async function renderEditor() {
  current = Math.min(current, slides.length - 1);
  // Miniaturas
  $('slides').innerHTML = slides.map((s, i) => `
    <div class="thumb ${i === current ? 'on' : ''}" data-i="${i}">
      <canvas width="384" height="216"></canvas>
      <div class="tb"><span>${i + 1}. ${s.type === 'esquela' ? 'Esquela' : 'Imagen'}</span>
        <span><button class="x" data-up="${i}" title="Subir">↑</button><button class="x" data-down="${i}" title="Bajar">↓</button><button class="x" data-del="${i}" title="Quitar">×</button></span></div>
    </div>`).join('');
  renderForm();
  const token = ++drawToken;
  await drawSlide($('preview').getContext('2d'), slides[current]);
  const big = document.createElement('canvas'); big.width = W; big.height = H;
  const thumbs = $('slides').querySelectorAll('canvas');
  for (let i = 0; i < slides.length; i++) {
    if (token !== drawToken) return;
    await drawSlide(big.getContext('2d'), slides[i]);
    thumbs[i]?.getContext('2d').drawImage(big, 0, 0, 384, 216);
  }
}

async function redrawCurrent() {
  saveSlides();
  await drawSlide($('preview').getContext('2d'), slides[current]);
  const big = document.createElement('canvas'); big.width = W; big.height = H;
  await drawSlide(big.getContext('2d'), slides[current]);
  $('slides').querySelectorAll('canvas')[current]?.getContext('2d').drawImage(big, 0, 0, 384, 216);
}

function field(label, html) { return `<label>${label}</label><div>${html}</div>`; }

function renderForm() {
  const s = slides[current];
  if (s.type === 'esquela') {
    $('slide-form').innerHTML = `<div class="form">
      ${field('', `<button class="ghost" data-random>Rellenar con un ejemplo aleatorio</button>`)}
      ${field('Encabezado', `<input type="text" data-k="heading" value="${esc(s.heading)}" placeholder="La Señora / El Señor">`)}
      ${field('Nombre', `<input type="text" data-k="name" value="${esc(s.name)}" placeholder="Dña. Nombre Apellido Apellido">`)}
      ${field('Edad', `<input type="text" data-k="age" value="${esc(s.age)}" placeholder="Ej.: 87 (se muestra «87 años»), o vacío" style="max-width:340px">`)}
      ${field('Detalles', `<textarea data-k="details" rows="5" placeholder="Conducción del cadáver:&#10;Viernes a las 15:30 h&#10;Iglesia y cementerio parroquiales de ...">${esc(s.details)}</textarea>`)}
      ${field('', `<span class="hint" style="margin:0">Las líneas que terminan en «:» salen en negrita.</span>`)}
      ${field('Imagen', `<input type="file" accept="image/*" data-file="photo"> ${s.photo ? '<button class="ghost" data-clear="photo">Quitar imagen</button>' : ''}`)}
      ${field('Posición', `<select data-k="photoPos" style="width:auto">
        <option value="debajo">Debajo de la esquela, alargada (servicio, lugar…)</option>
        <option value="izquierda">A la izquierda, vertical (foto de la persona)</option></select>`)}
      ${field('', `<div class="chk">
        <label><input type="checkbox" data-k="cross" ${s.cross ? 'checked' : ''}> Cruz</label>
        <label><input type="checkbox" data-k="dep" ${s.dep ? 'checked' : ''}> D.E.P.</label></div>`)}
    </div>`;
    $('slide-form').querySelector('[data-k=photoPos]').value = s.photoPos === 'izquierda' ? 'izquierda' : 'debajo';
  } else {
    $('slide-form').innerHTML = `<div class="form">
      ${field('Imagen', `<input type="file" accept="image/*" data-file="image">`)}
      ${field('Ajuste', `<select data-k="fit" style="width:auto"><option value="contain">Entera (con bandas negras)</option><option value="cover">Llenar la pantalla (recorta)</option></select>`)}
    </div>`;
    $('slide-form').querySelector('[data-k=fit]').value = s.fit;
  }
}

$('slide-form').addEventListener('input', e => {
  const k = e.target.dataset.k; if (!k) return;
  slides[current][k] = e.target.type === 'checkbox' ? e.target.checked : e.target.value;
  redrawCurrent();
});
$('slide-form').addEventListener('change', async e => {
  const k = e.target.dataset.file;
  if (k && e.target.files[0]) {
    try { slides[current][k] = await readImage(e.target.files[0]); renderEditor(); saveSlides(); }
    catch (err) { notice(err.message, 'bad'); }
  } else if (e.target.dataset.k && e.target.tagName === 'SELECT') {
    slides[current][e.target.dataset.k] = e.target.value; redrawCurrent();
  }
});
$('slide-form').addEventListener('click', e => {
  if (e.target.dataset.random !== undefined) {
    const s = slides[current];
    const filled = [s.heading, s.name, s.age, s.details].some(v => String(v || '').trim());
    if (filled && !confirm('¿Sustituir el texto de esta esquela por un ejemplo aleatorio? (La imagen se mantiene)')) return;
    slides[current] = randomEsquela(s); saveSlides(); renderEditor();
    return;
  }
  const k = e.target.dataset.clear; if (!k) return;
  slides[current][k] = null; saveSlides(); renderEditor();
});
$('slides').addEventListener('click', e => {
  const t = e.target.dataset;
  if (t.up != null) { const i = +t.up; if (i > 0) { [slides[i - 1], slides[i]] = [slides[i], slides[i - 1]]; current = i - 1; } }
  else if (t.down != null) { const i = +t.down; if (i < slides.length - 1) { [slides[i + 1], slides[i]] = [slides[i], slides[i + 1]]; current = i + 1; } }
  else if (t.del != null) {
    if (slides.length === 1) { notice('Tiene que quedar al menos una diapositiva.', 'bad'); return; }
    if (!confirm('¿Quitar esta diapositiva?')) return;
    slides.splice(+t.del, 1);
  } else {
    const th = e.target.closest('.thumb'); if (!th) return; current = +th.dataset.i;
  }
  saveSlides(); renderEditor();
});
$('add-esq').onclick = () => { slides.push(newEsquela()); current = slides.length - 1; saveSlides(); renderEditor(); };
$('add-img').onclick = () => { slides.push(newImage()); current = slides.length - 1; saveSlides(); renderEditor(); };
$('example-slides').onclick = () => {
  if (!confirm('¿Cambiar lo que hay en el editor por la esquela de ejemplo?')) return;
  slides = [exampleEsquela()]; current = 0; saveSlides(); renderEditor();
};
$('clear-slides').onclick = () => {
  if (!confirm('¿Vaciar el editor y empezar de nuevo?')) return;
  slides = [newEsquela()]; current = 0; saveSlides(); renderEditor();
};

let slideSecsTouched = false;
$('slide-secs').oninput = () => { slideSecsTouched = true; };

let sendingSlides = false;
// Dibuja las diapositivas del editor, las envía a los equipos y las muestra al momento.
async function sendSlides(list) {
  const secs = parseInt($('slide-secs').value, 10);
  if (!(secs >= 1 && secs <= 600)) { notice('Los segundos por diapositiva deben estar entre 1 y 600.', 'bad'); return; }
  if (slides.some(s => s.type === 'imagen' && !s.image)) { notice('Hay una diapositiva de imagen sin imagen elegida.', 'bad'); return; }
  const dudosa = slides.some(s => s.type === 'esquela' && (!s.name.trim() || s.name.trim() === 'Dña. Nombre Apellido Apellido'));
  if (dudosa && !confirm('Alguna esquela no tiene nombre o sigue con el nombre de ejemplo. ¿Enviarla igualmente?')) return;
  const btn = document.querySelector('[data-mode=esquela]');
  sendingSlides = true; btn.disabled = true; $('slides-prog').hidden = false; $('slides-prog').value = 0;
  try {
    const big = document.createElement('canvas'); big.width = W; big.height = H;
    const out = [];
    for (let i = 0; i < slides.length; i++) {
      await drawSlide(big.getContext('2d'), slides[i]);
      out.push(big.toDataURL('image/jpeg', 0.9));
      $('slides-prog').value = 100 * (i + 1) / slides.length;
    }
    const first = slides.find(s => s.type === 'esquela' && s.name.trim());
    await post('/api/slideshow', { ips: list, seconds: secs, slides: out, name: first ? first.name.trim() : 'Diapositivas' });
    slideSecsTouched = false;
    notice(`Enviando ${out.length} diapositiva(s) a ${list.length} equipo(s)…`, 'warn');
    await refresh();
  } catch (e) { notice(e.message, 'bad'); }
  sendingSlides = false; btn.disabled = false; $('slides-prog').hidden = true;
}

renderEditor();

refresh();
setInterval(refresh, 1500);
</script>
</body>
</html>
"""


# =========================================================================== arranque

def main():
    port = int(CFG.get("port") or 8000)
    try:
        ThreadingHTTPServer.allow_reuse_address = os.name != "nt"
        server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    except OSError as e:
        print(f"No se pudo abrir el puerto {port}: {e}")
        print("¿Está ya abierto el panel central? Puedes cambiar el puerto en panel_central.json.")
        try:
            input("Pulsa Intro para salir…")
        except EOFError:  # sin consola (por ejemplo, arrancado en segundo plano)
            pass
        return
    server.daemon_threads = True
    threading.Thread(target=poller, daemon=True).start()

    print("RTSP Viewer · Panel central")
    print(f"  En este PC:        http://localhost:{port}")
    if primary_ip():
        print(f"  Desde otro equipo: http://{primary_ip()}:{port}")
    print(f"  Configuración:     {CONFIG_PATH}")
    print("\nCierra esta ventana para detener el panel (los equipos siguen funcionando).")
    if CFG.get("open_browser"):
        threading.Timer(0.8, lambda: webbrowser.open(f"http://localhost:{port}")).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

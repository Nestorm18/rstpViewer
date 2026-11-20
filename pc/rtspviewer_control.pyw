"""
RTSP Viewer · Control
Programa portable para Windows que gestiona por red el modo respaldo de la app
RTSP Viewer del TV stick (puerto 8080). Solo usa la biblioteca estándar de Python.

La configuración se guarda en rtspviewer_control.json junto a este archivo (o junto al .exe).
"""

import http.client
import ipaddress
import json
import os
import queue
import socket
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from tkinter import filedialog, messagebox, ttk

APP_ID = "RTSPViewer"
PORT = 8080
REFRESH_MS = 4000
TIMEOUT = 5
SCAN_TIMEOUT = 0.8
CHUNK = 256 * 1024

IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp"}
VIDEO_EXT = {".mp4", ".mkv", ".webm"}
PPT_EXT = {".pptx", ".ppt", ".ppsx", ".pps"}

MODES = [("auto", "Automático"), ("respaldo", "Siempre respaldo"), ("camara", "Siempre cámara")]
CAMERA_TEXT = {"ok": "Funciona", "fallo": "Fallo", "conectando": "Conectando…", "parada": "Parada (modo respaldo)"}
SHOWING_TEXT = {"camara": "Cámara", "respaldo": "Respaldo", "ninguno": "Nada (la app no está en pantalla)"}


def base_dir():
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


CONFIG_PATH = os.path.join(base_dir(), "rtspviewer_control.json")
DEFAULT_CONFIG = {"ip": "", "seconds_per_slide": 8}


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            cfg.update(json.load(f))
    except (OSError, ValueError):
        pass
    return cfg


def save_config(cfg):
    try:
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2, ensure_ascii=False)
    except OSError:
        pass


def mb(n):
    return f"{n / 1048576:.1f} MB"


# --------------------------------------------------------------------------- red

class ApiError(Exception):
    """Error con un mensaje listo para mostrar al usuario."""


def friendly(exc, ip):
    if isinstance(exc, ApiError):
        return str(exc)
    if isinstance(exc, socket.timeout):
        return f"El stick ({ip}) no responde: tiempo agotado. ¿Está encendido y en la misma red?"
    if isinstance(exc, ConnectionRefusedError):
        return f"El stick ({ip}) rechaza la conexión: ¿está abierta la app RTSP Viewer?"
    if isinstance(exc, (ConnectionResetError, BrokenPipeError, http.client.RemoteDisconnected)):
        return "Se cortó la conexión con el stick."
    if isinstance(exc, socket.gaierror):
        return f"La dirección «{ip}» no es válida."
    if isinstance(exc, OSError):
        return f"No se puede llegar al stick ({ip}): {exc.strerror or exc}"
    return f"Error inesperado: {exc}"


def read_json(resp):
    raw = resp.read()
    try:
        data = json.loads(raw.decode("utf-8"))
    except ValueError:
        raise ApiError(f"Respuesta no válida del stick (HTTP {resp.status})")
    if resp.status != 200:
        raise ApiError(data.get("error") or f"Error HTTP {resp.status}")
    return data


def api_get(ip, path, timeout=TIMEOUT):
    conn = http.client.HTTPConnection(ip, PORT, timeout=timeout)
    try:
        conn.request("GET", path)
        return read_json(conn.getresponse())
    finally:
        conn.close()


def api_post(ip, path, body=b"", timeout=TIMEOUT):
    if isinstance(body, str):
        body = body.encode("utf-8")
    conn = http.client.HTTPConnection(ip, PORT, timeout=timeout)
    try:
        conn.request("POST", path, body=body, headers={"Content-Type": "text/plain; charset=utf-8"})
        return read_json(conn.getresponse())
    finally:
        conn.close()


def api_upload(ip, path, progress):
    """Sube un archivo en el cuerpo de la petición. progress(enviados, total)."""
    size = os.path.getsize(path)
    name = os.path.basename(path)
    conn = http.client.HTTPConnection(ip, PORT, timeout=60)
    try:
        conn.putrequest("POST", "/api/upload?name=" + urllib.parse.quote(name))
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
            pass  # el stick puede cortar al rechazar el archivo: leer su mensaje de error
        return read_json(conn.getresponse())
    finally:
        conn.close()


def local_networks():
    """Redes /24 de las IPv4 privadas de este PC (la de salida primero)."""
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))  # no envía nada; solo elige la interfaz
        ips.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
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
        data = api_get(str(ip), "/api/status", timeout=SCAN_TIMEOUT)
        return str(ip) if data.get("app") == APP_ID else None
    except Exception:
        return None


# --------------------------------------------------------------------------- PowerPoint

PS_SCRIPT = r"""
param([string]$In, [string]$OutDir, [int]$Seconds)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$code = 0
$app = $null
$pres = $null
try {
    try { $app = New-Object -ComObject PowerPoint.Application }
    catch { throw "No se puede abrir PowerPoint. ¿Está instalado?" }
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


def convert_powerpoint(path, seconds):
    """Convierte con PowerPoint (COM): 1 diapositiva → JPG, varias → MP4. Devuelve (ruta, nº diapositivas)."""
    work = tempfile.mkdtemp(prefix="rtspviewer_")
    script = os.path.join(work, "convertir.ps1")
    with open(script, "w", encoding="utf-8-sig") as f:
        f.write(PS_SCRIPT)
    cmd = ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script,
           "-In", os.path.abspath(path), "-OutDir", work, "-Seconds", str(int(seconds))]
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=3600,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except subprocess.TimeoutExpired:
        raise ApiError("La conversión de PowerPoint tardó demasiado (más de 1 hora).")
    except OSError as e:
        raise ApiError(f"No se pudo ejecutar PowerShell: {e}")
    out = r.stdout.decode("utf-8", "replace")
    err = r.stderr.decode("utf-8", "replace").strip()
    for line in out.splitlines():
        if line.startswith("OK|"):
            _, n, result = line.split("|", 2)
            return result.strip(), int(n)
    raise ApiError("Error al convertir el PowerPoint: " + (err.splitlines()[-1] if err else "sin detalles"))


# --------------------------------------------------------------------------- interfaz

class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("RTSP Viewer · Control")
        self.minsize(560, 0)
        self.cfg = load_config()
        self.events = queue.Queue()
        self.busy = False          # operación larga en curso (subida, conversión, búsqueda)
        self.refreshing = False
        self.mode_sending = False
        self.last_mode = None

        self._build()
        self.ip_var.set(self.cfg.get("ip", ""))
        self.after(100, self._poll_events)
        self.after(300, self._auto_refresh)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    # ---- construcción

    def _build(self):
        pad = {"padx": 10, "pady": 6}
        root = ttk.Frame(self, padding=8)
        root.pack(fill="both", expand=True)
        root.columnconfigure(0, weight=1)

        # Conexión
        f = ttk.LabelFrame(root, text="Stick")
        f.grid(row=0, column=0, sticky="ew", **pad)
        ttk.Label(f, text="IP del stick:").grid(row=0, column=0, padx=6, pady=8)
        self.ip_var = tk.StringVar()
        e = ttk.Entry(f, textvariable=self.ip_var, width=18)
        e.grid(row=0, column=1, padx=4, pady=8, sticky="w")
        e.bind("<Return>", lambda _: self.check())
        self.btn_scan = ttk.Button(f, text="Buscar", command=self.scan)
        self.btn_scan.grid(row=0, column=2, padx=4)
        ttk.Button(f, text="Comprobar", command=self.check).grid(row=0, column=3, padx=4)

        # Estado
        f = ttk.LabelFrame(root, text="Estado")
        f.grid(row=1, column=0, sticky="ew", **pad)
        f.columnconfigure(1, weight=1)
        self.st = {}
        for i, (key, label) in enumerate([("camera", "Cámara"), ("showing", "En pantalla"), ("mode", "Modo"),
                                          ("file", "Archivo"), ("free", "Espacio libre"), ("updated", "Actualizado")]):
            ttk.Label(f, text=label + ":", foreground="#666").grid(row=i, column=0, sticky="w", padx=6, pady=1)
            v = ttk.Label(f, text="–")
            v.grid(row=i, column=1, sticky="w", padx=6, pady=1)
            self.st[key] = v

        # Modo
        f = ttk.LabelFrame(root, text="Modo")
        f.grid(row=2, column=0, sticky="ew", **pad)
        self.mode_var = tk.StringVar()
        cb = ttk.Combobox(f, textvariable=self.mode_var, state="readonly", width=28,
                          values=[label for _, label in MODES])
        cb.grid(row=0, column=0, padx=6, pady=8, sticky="w")
        cb.bind("<<ComboboxSelected>>", lambda _: self.send_mode())
        self.mode_cb = cb

        # Archivo
        f = ttk.LabelFrame(root, text="Archivo de respaldo (imagen, vídeo o PowerPoint)")
        f.grid(row=3, column=0, sticky="ew", **pad)
        f.columnconfigure(0, weight=1)
        row = ttk.Frame(f)
        row.grid(row=0, column=0, sticky="ew", padx=6, pady=(8, 4))
        self.btn_send_file = ttk.Button(row, text="Elegir archivo y enviar…", command=self.choose_file)
        self.btn_send_file.pack(side="left")
        self.btn_delete = ttk.Button(row, text="Borrar archivo de respaldo", command=self.delete_file)
        self.btn_delete.pack(side="left", padx=8)
        row2 = ttk.Frame(f)
        row2.grid(row=1, column=0, sticky="w", padx=6, pady=2)
        ttk.Label(row2, text="PowerPoint con varias diapositivas: segundos por diapositiva").pack(side="left")
        self.secs_var = tk.IntVar(value=int(self.cfg.get("seconds_per_slide", 8)))
        ttk.Spinbox(row2, from_=1, to=600, width=5, textvariable=self.secs_var).pack(side="left", padx=6)
        self.progress = ttk.Progressbar(f, mode="determinate", maximum=100)
        self.progress.grid(row=2, column=0, sticky="ew", padx=6, pady=(4, 2))
        self.progress_lbl = ttk.Label(f, text="", foreground="#666")
        self.progress_lbl.grid(row=3, column=0, sticky="w", padx=6, pady=(0, 6))

        # Texto
        f = ttk.LabelFrame(root, text="Texto de respaldo (se muestra si no hay archivo)")
        f.grid(row=4, column=0, sticky="ew", **pad)
        f.columnconfigure(0, weight=1)
        self.text = tk.Text(f, height=3, wrap="word", font=("Segoe UI", 10))
        self.text.grid(row=0, column=0, sticky="ew", padx=6, pady=(8, 4))
        ttk.Button(f, text="Enviar texto", command=self.send_text).grid(row=1, column=0, sticky="w", padx=6, pady=(0, 8))
        self.text_loaded = False

        # Mensajes
        self.msg = ttk.Label(root, text="", wraplength=540)
        self.msg.grid(row=5, column=0, sticky="ew", padx=10, pady=(4, 8))

    # ---- utilidades

    def ip(self):
        return self.ip_var.get().strip()

    def say(self, text, error=False):
        self.msg.configure(text=text, foreground="#c62828" if error else "#2e7d32")

    def run(self, work, done=None, fail=None):
        """Ejecuta work() en un hilo; done(resultado) / fail(mensaje) vuelven al hilo de la interfaz."""
        ip = self.ip()

        def target():
            try:
                res = work()
                self.events.put((done, res) if done else None)
            except Exception as e:  # noqa: BLE001
                msg = friendly(e, ip)
                self.events.put((fail or (lambda m: self.say(m, True)), msg))

        threading.Thread(target=target, daemon=True).start()

    def _poll_events(self):
        try:
            while True:
                ev = self.events.get_nowait()
                if ev:
                    fn, arg = ev
                    fn(arg)
        except queue.Empty:
            pass
        self.after(100, self._poll_events)

    def need_ip(self):
        if not self.ip():
            self.say("Escribe la IP del stick o pulsa «Buscar».", True)
            return False
        return True

    def set_busy(self, busy):
        self.busy = busy
        state = "disabled" if busy else "normal"
        for b in (self.btn_send_file, self.btn_delete, self.btn_scan):
            b.configure(state=state)

    # ---- estado

    def show_status(self, s):
        self.st["camera"].configure(text=CAMERA_TEXT.get(s.get("camera"), s.get("camera", "?")),
                                    foreground={"ok": "#2e7d32", "fallo": "#c62828"}.get(s.get("camera"), ""))
        self.st["showing"].configure(text=SHOWING_TEXT.get(s.get("showing"), s.get("showing", "?")))
        mode = s.get("mode")
        self.st["mode"].configure(text=dict(MODES).get(mode, mode))
        f = s.get("file")
        self.st["file"].configure(text=f"{f['name']} ({f['type']}, {mb(f['size'])})" if f else "Ninguno (se muestra el texto)")
        self.st["free"].configure(text=mb(s.get("freeBytes", 0)))
        self.st["updated"].configure(text=time.strftime("%H:%M:%S"))
        if not self.mode_sending and mode != self.last_mode:
            self.last_mode = mode
            self.mode_var.set(dict(MODES).get(mode, ""))
        if not self.text_loaded:
            self.text.delete("1.0", "end")
            self.text.insert("1.0", s.get("text", ""))
            self.text_loaded = True
        self.free_bytes = s.get("freeBytes", 0)

    def clear_status(self):
        for v in self.st.values():
            v.configure(text="–", foreground="")

    def check(self):
        if not self.need_ip():
            return
        ip = self.ip()
        self.cfg["ip"] = ip
        save_config(self.cfg)
        self.text_loaded = False

        def done(s):
            self.show_status(s)
            self.say(f"Conectado con RTSP Viewer {s.get('version', '')} en {ip}.")

        def fail(m):
            self.clear_status()
            self.say(m, True)

        self.run(lambda: self._status_checked(ip), done, fail)

    @staticmethod
    def _status_checked(ip):
        s = api_get(ip, "/api/status")
        if s.get("app") != APP_ID:
            raise ApiError(f"En {ip}:{PORT} responde algo que no es RTSP Viewer.")
        return s

    def _auto_refresh(self):
        if self.ip() and not self.refreshing and not self.busy:
            self.refreshing = True
            ip = self.ip()

            def done(s):
                self.refreshing = False
                self.show_status(s)

            def fail(m):
                self.refreshing = False
                self.st["updated"].configure(text=time.strftime("%H:%M:%S") + " · sin conexión")

            self.run(lambda: api_get(ip, "/api/status"), done, fail)
        self.after(REFRESH_MS, self._auto_refresh)

    # ---- búsqueda

    def scan(self):
        nets = local_networks()
        try:  # priorizar la red de la IP escrita
            typed = ipaddress.ip_network(f"{self.ip()}/24", strict=False)
            if typed.is_private:
                nets = [typed] + [n for n in nets if n != typed]
        except ValueError:
            pass
        if not nets:
            self.say("No se encontró ninguna red local en este PC.", True)
            return
        self.set_busy(True)
        self.say("Buscando en " + ", ".join(str(n) for n in nets) + "…", False)
        self.progress.configure(mode="indeterminate")
        self.progress.start(12)

        def work():
            found = []
            with ThreadPoolExecutor(max_workers=64) as ex:
                for net in nets:
                    found += [ip for ip in ex.map(probe, net.hosts()) if ip]
            return found

        def done(found):
            self._stop_progress()
            self.set_busy(False)
            if not found:
                self.say("No se encontró RTSP Viewer en la red. ¿Está la app abierta en el stick?", True)
                return
            self.ip_var.set(found[0])
            if len(found) > 1:
                self.say("Encontrados varios: " + ", ".join(found) + f". Se usa {found[0]}.")
            self.check()

        def fail(m):
            self._stop_progress()
            self.set_busy(False)
            self.say(m, True)

        self.run(work, done, fail)

    # ---- modo, texto, borrar

    def send_mode(self):
        if not self.need_ip():
            return
        label = self.mode_var.get()
        mode = next(k for k, v in MODES if v == label)
        self.mode_sending = True
        ip = self.ip()

        def done(s):
            self.mode_sending = False
            self.last_mode = None
            self.show_status(s)
            self.say(f"Modo cambiado a «{label}».")

        def fail(m):
            self.mode_sending = False
            self.last_mode = None
            self.say(m, True)

        self.run(lambda: api_post(ip, "/api/mode", mode), done, fail)

    def send_text(self):
        if not self.need_ip():
            return
        txt = self.text.get("1.0", "end").strip()
        if len(txt) > 500:
            self.say("El texto es demasiado largo (máximo 500 caracteres).", True)
            return
        ip = self.ip()

        def done(s):
            self.show_status(s)
            self.say("Texto enviado." + ("" if txt else " (vacío: se usa el texto por defecto)"))

        self.run(lambda: api_post(ip, "/api/text", txt), done)

    def delete_file(self):
        if not self.need_ip():
            return
        if not messagebox.askyesno("Borrar archivo", "¿Borrar el archivo de respaldo del stick?", parent=self):
            return
        ip = self.ip()

        def done(s):
            self.show_status(s)
            self.say("Archivo de respaldo borrado.")

        self.run(lambda: api_post(ip, "/api/delete"), done)

    # ---- archivo

    def choose_file(self):
        if not self.need_ip():
            return
        path = filedialog.askopenfilename(parent=self, title="Elegir archivo de respaldo", filetypes=[
            ("Imagen, vídeo o PowerPoint", "*.jpg *.jpeg *.png *.webp *.mp4 *.mkv *.webm *.pptx *.ppt *.ppsx *.pps"),
            ("Imágenes", "*.jpg *.jpeg *.png *.webp"),
            ("Vídeos", "*.mp4 *.mkv *.webm"),
            ("PowerPoint", "*.pptx *.ppt *.ppsx *.pps"),
        ])
        if not path:
            return
        ext = os.path.splitext(path)[1].lower()
        if ext not in IMAGE_EXT | VIDEO_EXT | PPT_EXT:
            self.say("Formato no admitido. Usa JPG, PNG, WEBP, MP4, MKV, WEBM o PowerPoint.", True)
            return
        try:
            secs = max(1, int(self.secs_var.get()))
        except (tk.TclError, ValueError):
            self.say("Los segundos por diapositiva deben ser un número.", True)
            return
        self.cfg["seconds_per_slide"] = secs
        save_config(self.cfg)

        ip = self.ip()
        self.set_busy(True)
        self.progress.configure(mode="determinate", value=0)

        def progress(sent, total):
            self.events.put((self._show_progress, (sent, total)))

        def work():
            upload_path = path
            if ext in PPT_EXT:
                self.events.put((self._converting, os.path.basename(path)))
                upload_path, n = convert_powerpoint(path, secs)
                kind = "imagen" if n == 1 else f"vídeo ({n} diapositivas × {secs} s)"
                self.events.put((self.say, f"Convertido a {kind}. Enviando…"))
            size = os.path.getsize(upload_path)
            s = api_get(ip, "/api/status")
            free = s.get("freeBytes", 0)
            if size + 50 * 1048576 > free:
                raise ApiError(f"No cabe en el stick: el archivo ocupa {mb(size)} y quedan {mb(free)} libres.")
            self.events.put((self._start_upload, os.path.basename(upload_path)))
            return api_upload(ip, upload_path, progress), os.path.basename(upload_path)

        def done(res):
            s, name = res
            self._stop_progress(100)
            self.set_busy(False)
            self.show_status(s)
            self.say(f"«{name}» enviado. Ya es el contenido de respaldo.")

        def fail(m):
            self._stop_progress()
            self.set_busy(False)
            self.say(m, True)

        self.run(work, done, fail)

    def _converting(self, name):
        self.progress.configure(mode="indeterminate")
        self.progress.start(12)
        self.progress_lbl.configure(text=f"Convirtiendo «{name}» con PowerPoint… (puede tardar unos minutos)")
        self.say("")

    def _start_upload(self, name):
        self.progress.stop()
        self.progress.configure(mode="determinate", value=0)
        self.progress_lbl.configure(text=f"Enviando «{name}»…")

    def _show_progress(self, args):
        sent, total = args
        self.progress.configure(value=100 * sent / total if total else 0)
        self.progress_lbl.configure(text=f"Enviando… {mb(sent)} de {mb(total)} ({100 * sent // max(total, 1)} %)")

    def _stop_progress(self, value=0):
        self.progress.stop()
        self.progress.configure(mode="determinate", value=value)
        self.progress_lbl.configure(text="")

    def _on_close(self):
        if self.ip():
            self.cfg["ip"] = self.ip()
        save_config(self.cfg)
        self.destroy()


if __name__ == "__main__":
    App().mainloop()

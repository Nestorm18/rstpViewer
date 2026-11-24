"""Crea panel_central.ico, el icono de PanelCentral.exe.

Mismo estilo que el icono de la app de la TV (fondo azul, dibujo blanco y punto rojo),
pero con un monitor que muestra cuatro pantallas: el panel que gestiona varios equipos.
Requiere Pillow (pip install pillow). Uso: python crear_icono.py
"""
import os

from PIL import Image, ImageDraw

AZUL = (2, 119, 189, 255)      # #0277BD, como el icono de la app
AZUL_OSCURO = (1, 87, 155, 255)
BLANCO = (255, 255, 255, 255)
ROJO = (229, 57, 53, 255)      # #E53935
S = 1024                       # se dibuja grande y se reduce para que los bordes queden suaves


def dibujar():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    u = S / 48  # mismas unidades que el icono de la app (48 x 48)

    def r(x0, y0, x1, y1):
        return [round(x0 * u), round(y0 * u), round(x1 * u), round(y1 * u)]

    # Fondo azul con esquinas redondeadas
    d.rounded_rectangle(r(0, 0, 48, 48), radius=round(9 * u), fill=AZUL)
    # Pie del monitor
    d.rectangle(r(21.5, 34, 26.5, 38.5), fill=BLANCO)
    d.rounded_rectangle(r(15, 38, 33, 41), radius=round(1.5 * u), fill=BLANCO)
    # Monitor
    d.rounded_rectangle(r(6, 9, 42, 35), radius=round(3 * u), fill=BLANCO)
    # Cuatro pantallas (2 x 2)
    gap, x0, y0, x1, y1 = 1.6, 9, 12, 39, 32
    w, h = (x1 - x0 - gap) / 2, (y1 - y0 - gap) / 2
    for i in range(2):
        for j in range(2):
            a, b = x0 + i * (w + gap), y0 + j * (h + gap)
            d.rounded_rectangle(r(a, b, a + w, b + h), radius=round(1 * u), fill=AZUL if (i + j) % 2 == 0 else AZUL_OSCURO)
    # Punto rojo de «en directo», como en el icono de la app
    cx, cy, rad = 13.5, 16.5, 2.6
    d.ellipse(r(cx - rad, cy - rad, cx + rad, cy + rad), fill=ROJO)
    return im


def main():
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "panel_central.ico")
    im = dibujar().resize((256, 256), Image.LANCZOS)
    im.save(out, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("Creado:", out)


if __name__ == "__main__":
    main()

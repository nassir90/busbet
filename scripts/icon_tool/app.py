import sys
import subprocess
import tempfile
from pathlib import Path

import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Gtk, Adw, Gio, GLib, Gdk, GdkPixbuf

RES_DIR = Path(__file__).parent.parent.parent / "tfi-app/app/src/main/res"

VARIANTS = [
    ("mdpi",    48),
    ("hdpi",    72),
    ("xhdpi",   96),
    ("xxhdpi",  144),
    ("xxxhdpi", 192),
]


def generate_icons(source_path: Path) -> None:
    for density, size in VARIANTS:
        for name, round_ in [("ic_launcher", False), ("ic_launcher_round", True)]:
            dest = RES_DIR / f"mipmap-{density}" / f"{name}.png"
            dest.parent.mkdir(parents=True, exist_ok=True)
            if round_:
                r = size // 2
                subprocess.run([
                    "magick", str(source_path),
                    "-resize", f"{size}x{size}",
                    "-alpha", "set",
                    "(",
                        "+clone", "-alpha", "transparent",
                        "-fill", "white",
                        "-draw", f"circle {r},{r} {r},0",
                    ")",
                    "-compose", "DstIn", "-composite",
                    str(dest),
                ], check=True)
            else:
                subprocess.run([
                    "magick", str(source_path),
                    "-resize", f"{size}x{size}",
                    str(dest),
                ], check=True)


class IconToolApp(Adw.Application):
    def __init__(self):
        super().__init__(
            application_id="com.busbet.icontool",
            flags=Gio.ApplicationFlags.DEFAULT_FLAGS,
        )

    def do_activate(self):
        win = MainWindow(application=self)
        win.present()


class MainWindow(Adw.ApplicationWindow):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.set_title("Icon Tool")
        self.set_default_size(640, 520)
        self.set_resizable(False)
        self._pending_path: Path | None = None
        self._build_ui()
        self._setup_paste()

    def _build_ui(self):
        toolbar = Adw.ToolbarView()
        header = Adw.HeaderBar()
        toolbar.add_top_bar(header)

        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        root.set_margin_top(16)
        root.set_margin_bottom(16)
        root.set_margin_start(16)
        root.set_margin_end(16)
        toolbar.set_content(root)
        self.set_content(toolbar)

        # ── Top row: current icon + pending preview ──────────────────────────
        previews = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=32)
        previews.set_halign(Gtk.Align.CENTER)

        current_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        current_box.set_halign(Gtk.Align.CENTER)
        current_label = Gtk.Label(label="Current icon")
        current_label.add_css_class("caption")
        self._current_image = Gtk.Image()
        self._current_image.set_pixel_size(96)
        current_box.append(current_label)
        current_box.append(self._current_image)

        pending_inner = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        pending_inner.set_halign(Gtk.Align.CENTER)
        pending_label = Gtk.Label(label="New icon — paste or click to choose")
        pending_label.add_css_class("caption")
        self._pending_image = Gtk.Image()
        self._pending_image.set_pixel_size(96)
        self._pending_image.set_opacity(0.35)
        pending_inner.append(pending_label)
        pending_inner.append(self._pending_image)

        self._pending_btn = Gtk.Button()
        self._pending_btn.add_css_class("pending-drop")
        self._pending_btn.set_child(pending_inner)
        self._pending_btn.set_halign(Gtk.Align.CENTER)
        self._pending_btn.connect("clicked", self._on_pick_file)

        previews.append(current_box)
        previews.append(self._pending_btn)
        root.append(previews)

        # ── Variants grid ────────────────────────────────────────────────────
        frame = Gtk.Frame(label="Variants on disk")
        grid = Gtk.Grid()
        grid.set_row_spacing(8)
        grid.set_column_spacing(16)
        grid.set_margin_top(8)
        grid.set_margin_bottom(8)
        grid.set_margin_start(12)
        grid.set_margin_end(12)

        headers = ["Density", "Size", "Square", "Round"]
        for col, h in enumerate(headers):
            lbl = Gtk.Label(label=h)
            lbl.add_css_class("caption-heading")
            lbl.set_halign(Gtk.Align.START)
            grid.attach(lbl, col, 0, 1, 1)

        self._variant_images: dict[str, tuple[Gtk.Image, Gtk.Image]] = {}
        for row, (density, size) in enumerate(VARIANTS, start=1):
            grid.attach(Gtk.Label(label=density, halign=Gtk.Align.START), 0, row, 1, 1)
            grid.attach(Gtk.Label(label=f"{size}×{size}", halign=Gtk.Align.START), 1, row, 1, 1)
            sq = Gtk.Image()
            sq.set_pixel_size(size if size <= 48 else 48)
            rnd = Gtk.Image()
            rnd.set_pixel_size(size if size <= 48 else 48)
            grid.attach(sq, 2, row, 1, 1)
            grid.attach(rnd, 3, row, 1, 1)
            self._variant_images[density] = (sq, rnd)

        frame.set_child(grid)
        root.append(frame)

        # ── Status + apply button ────────────────────────────────────────────
        bottom = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        bottom.set_halign(Gtk.Align.END)
        self._status = Gtk.Label(label="Paste an image anywhere to set a new icon")
        self._status.add_css_class("caption")
        self._status.set_hexpand(True)
        self._status.set_halign(Gtk.Align.START)
        self._apply_btn = Gtk.Button(label="Apply & write all sizes")
        self._apply_btn.add_css_class("suggested-action")
        self._apply_btn.set_sensitive(False)
        self._apply_btn.connect("clicked", self._on_apply)
        bottom.append(self._status)
        bottom.append(self._apply_btn)
        root.append(bottom)

        self._reload_current()

    def _setup_paste(self):
        ctrl = Gtk.EventControllerKey()
        ctrl.connect("key-pressed", self._on_key)
        self.add_controller(ctrl)
        self._apply_css()

    def _apply_css(self):
        provider = Gtk.CssProvider()
        provider.load_from_string("""
            .pending-drop {
                border: 2px dashed alpha(currentColor, 0.25);
                border-radius: 12px;
                padding: 12px;
                background: transparent;
            }
            .pending-drop:hover {
                border-color: @accent_color;
                background: alpha(@accent_color, 0.08);
            }
        """)
        Gtk.StyleContext.add_provider_for_display(
            self.get_display(),
            provider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION,
        )

    def _on_pick_file(self, _btn):
        dialog = Gtk.FileDialog()
        dialog.set_title("Choose icon image")
        f = Gio.File.new_for_path(str(Path.home()))
        dialog.set_initial_folder(f)
        png_filter = Gtk.FileFilter()
        png_filter.set_name("Images (PNG, JPEG, WebP)")
        for pat in ("*.png", "*.jpg", "*.jpeg", "*.webp"):
            png_filter.add_pattern(pat)
        filters = Gio.ListStore.new(Gtk.FileFilter)
        filters.append(png_filter)
        dialog.set_filters(filters)
        dialog.open(self, None, self._on_file_chosen)

    def _on_file_chosen(self, dialog, result):
        try:
            gfile = dialog.open_finish(result)
        except Exception:
            return
        if gfile:
            self._set_pending(Path(gfile.get_path()))

    def _on_key(self, ctrl, keyval, keycode, state):
        if (state & Gdk.ModifierType.CONTROL_MASK) and keyval == Gdk.KEY_v:
            clipboard = self.get_clipboard()
            clipboard.read_texture_async(None, self._on_clipboard_texture)
            return True
        return False

    def _on_clipboard_texture(self, clipboard, result):
        try:
            texture = clipboard.read_texture_finish(result)
        except Exception:
            # Try reading as a file path / text fallback
            clipboard.read_text_async(None, self._on_clipboard_text)
            return
        if texture is None:
            self._status.set_text("Nothing image-like on clipboard.")
            return
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            tmp = Path(f.name)
        texture.save_to_png(str(tmp))
        self._set_pending(tmp)

    def _on_clipboard_text(self, clipboard, result):
        try:
            text = clipboard.read_text_finish(result)
        except Exception:
            self._status.set_text("Could not read clipboard.")
            return
        if text:
            p = Path(text.strip())
            if p.exists() and p.suffix.lower() in (".png", ".jpg", ".jpeg", ".webp"):
                self._set_pending(p)
                return
        self._status.set_text("Clipboard didn't contain an image.")

    def _set_pending(self, path: Path):
        self._pending_path = path
        try:
            pb = GdkPixbuf.Pixbuf.new_from_file_at_scale(str(path), 96, 96, True)
            self._pending_image.set_from_pixbuf(pb)
            self._pending_image.set_opacity(1.0)
        except Exception as e:
            self._status.set_text(f"Could not load image: {e}")
            return
        self._apply_btn.set_sensitive(True)
        self._status.set_text(f"Ready to apply: {path.name}")

    def _on_apply(self, _btn):
        if self._pending_path is None:
            return
        self._apply_btn.set_sensitive(False)
        self._status.set_text("Writing…")

        def worker():
            try:
                generate_icons(self._pending_path)
                GLib.idle_add(self._on_done, None)
            except Exception as e:
                GLib.idle_add(self._on_done, str(e))

        import threading
        threading.Thread(target=worker, daemon=True).start()

    def _on_done(self, error):
        if error:
            self._status.set_text(f"Error: {error}")
            self._apply_btn.set_sensitive(True)
        else:
            self._status.set_text("Done — all sizes written.")
            self._reload_current()
            self._pending_path = None
            self._pending_image.clear()
            self._pending_image.set_opacity(0.35)
        return False

    def _reload_current(self):
        # Show the xxxhdpi square as the "current" icon
        xxxhdpi = RES_DIR / "mipmap-xxxhdpi" / "ic_launcher.png"
        if xxxhdpi.exists():
            pb = GdkPixbuf.Pixbuf.new_from_file_at_scale(str(xxxhdpi), 96, 96, True)
            self._current_image.set_from_pixbuf(pb)

        for density, size in VARIANTS:
            sq_path  = RES_DIR / f"mipmap-{density}" / "ic_launcher.png"
            rnd_path = RES_DIR / f"mipmap-{density}" / "ic_launcher_round.png"
            sq_img, rnd_img = self._variant_images[density]
            display = min(size, 48)
            if sq_path.exists():
                pb = GdkPixbuf.Pixbuf.new_from_file_at_scale(str(sq_path), display, display, True)
                sq_img.set_from_pixbuf(pb)
            if rnd_path.exists():
                pb = GdkPixbuf.Pixbuf.new_from_file_at_scale(str(rnd_path), display, display, True)
                rnd_img.set_from_pixbuf(pb)


def main():
    app = IconToolApp()
    sys.exit(app.run(sys.argv))

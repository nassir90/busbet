"""GTK front end for icon-tool.

All the actual work lives in core.py; this module is the window, the buttons and the threading
that keeps them responsive. Nothing here should grow logic that a CLI or an MCP server would
also need -- that belongs in core.
"""

import sys
import threading
from pathlib import Path

import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Gtk, Adw, Gio, GLib, Gdk, GdkPixbuf

from .core import (
    ROOT_DIR,
    VARIANTS,
    AdbDevice,
    AppProject,
    aab_is_signed,
    build_aab,
    build_app,
    discover_apps,
    find_aab,
    find_apk,
    generate_icons,
    install_apk,
    launch_app,
    list_adb_devices,
    reveal_in_file_manager,
)

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
        self.set_title("App Tool")
        self.set_default_size(640, 760)
        self.set_resizable(True)
        self._pending_path: Path | None = None
        self._apps = discover_apps(ROOT_DIR)
        if not self._apps:
            raise RuntimeError(f"No Android app projects found under {ROOT_DIR}")
        self._app_names = list(self._apps.keys())
        self._current_app = self._apps[self._app_names[0]]
        self._devices: list[AdbDevice] = []
        self._build_ui()
        self._setup_paste()
        self._refresh_devices()

    def _build_ui(self):
        toolbar = Adw.ToolbarView()
        header = Adw.HeaderBar()
        toolbar.add_top_bar(header)

        app_dropdown = Gtk.DropDown.new_from_strings(self._app_names)
        app_dropdown.set_selected(0)
        app_dropdown.connect("notify::selected", self._on_app_changed)
        header.pack_start(app_dropdown)

        stack = Adw.ViewStack()
        switcher = Adw.ViewSwitcher()
        switcher.set_stack(stack)
        header.set_title_widget(switcher)
        toolbar.set_content(stack)
        self.set_content(toolbar)

        icon_root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        icon_root.set_margin_top(16)
        icon_root.set_margin_bottom(16)
        icon_root.set_margin_start(16)
        icon_root.set_margin_end(16)
        stack.add_titled(icon_root, "icon", "Icon")
        stack.get_page(icon_root).set_icon_name("image-x-generic-symbolic")

        build_root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        build_root.set_margin_top(16)
        build_root.set_margin_bottom(16)
        build_root.set_margin_start(16)
        build_root.set_margin_end(16)
        stack.add_titled(build_root, "build", "Build & Install")
        stack.get_page(build_root).set_icon_name("applications-development-symbolic")

        root = icon_root

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

        self._remove_bg_check = Gtk.CheckButton(
            label="Remove background (flood-fill transparent from edges)",
        )
        self._remove_bg_check.set_halign(Gtk.Align.CENTER)
        root.append(self._remove_bg_check)

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

        # ── Build & install tab ─────────────────────────────────────────────
        build_frame = Gtk.Frame(label="Build & install")
        build_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        build_box.set_margin_top(8)
        build_box.set_margin_bottom(8)
        build_box.set_margin_start(12)
        build_box.set_margin_end(12)

        device_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        device_label = Gtk.Label(label="Device:")
        self._device_dropdown = Gtk.DropDown.new_from_strings(["(none detected)"])
        self._device_dropdown.set_hexpand(True)
        refresh_btn = Gtk.Button(icon_name="view-refresh-symbolic")
        refresh_btn.set_tooltip_text("Refresh ADB devices")
        refresh_btn.connect("clicked", lambda _b: self._refresh_devices())
        device_row.append(device_label)
        device_row.append(self._device_dropdown)
        device_row.append(refresh_btn)
        build_box.append(device_row)

        build_btn_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        build_btn_row.set_halign(Gtk.Align.END)
        self._build_status = Gtk.Label(label="")
        self._build_status.add_css_class("caption")
        self._build_status.set_hexpand(True)
        self._build_status.set_halign(Gtk.Align.START)
        self._build_btn = Gtk.Button(label="Build")
        self._build_btn.connect("clicked", self._on_build)
        self._build_install_btn = Gtk.Button(label="Build & Install")
        self._build_install_btn.add_css_class("suggested-action")
        self._build_install_btn.connect("clicked", self._on_build_install)
        self._open_btn = Gtk.Button(label="Open")
        self._open_btn.connect("clicked", self._on_open)
        build_btn_row.append(self._build_status)
        build_btn_row.append(self._build_btn)
        build_btn_row.append(self._build_install_btn)
        build_btn_row.append(self._open_btn)
        build_box.append(build_btn_row)

        # ── Release bundles ──────────────────────────────────────────────────
        # Separate row from the debug buttons on purpose: these produce the signed artifact that
        # goes to Play, and mixing them in with "Build & Install" invites the wrong click.
        aab_btn_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        aab_btn_row.set_halign(Gtk.Align.END)
        self._aab_status = Gtk.Label(label="")
        self._aab_status.add_css_class("caption")
        self._aab_status.set_hexpand(True)
        self._aab_status.set_halign(Gtk.Align.START)
        self._build_aab_btn = Gtk.Button(label="Build AAB")
        self._build_aab_btn.set_tooltip_text("Build the signed release bundle for the selected app")
        self._build_aab_btn.connect("clicked", self._on_build_aab)
        self._build_all_aab_btn = Gtk.Button(label="Build all AABs")
        self._build_all_aab_btn.set_tooltip_text("Build the release bundle for every discovered app")
        self._build_all_aab_btn.connect("clicked", self._on_build_all_aabs)
        self._show_aab_btn = Gtk.Button(label="Show AAB")
        self._show_aab_btn.set_tooltip_text("Reveal the built .aab in the file manager")
        self._show_aab_btn.connect("clicked", self._on_show_aab)
        aab_btn_row.append(self._aab_status)
        aab_btn_row.append(self._build_aab_btn)
        aab_btn_row.append(self._build_all_aab_btn)
        aab_btn_row.append(self._show_aab_btn)
        build_box.append(aab_btn_row)

        log_scroller = Gtk.ScrolledWindow()
        log_scroller.set_min_content_height(140)
        log_scroller.set_vexpand(True)
        log_scroller.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.AUTOMATIC)
        self._log_buffer = Gtk.TextBuffer()
        self._log_view = Gtk.TextView(buffer=self._log_buffer)
        self._log_view.set_editable(False)
        self._log_view.set_monospace(True)
        self._log_view.set_top_margin(6)
        self._log_view.set_bottom_margin(6)
        self._log_view.set_left_margin(6)
        log_scroller.set_child(self._log_view)
        build_box.append(log_scroller)

        build_box.set_vexpand(True)
        build_frame.set_child(build_box)
        build_frame.set_vexpand(True)
        build_root.append(build_frame)

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

    def _on_app_changed(self, dropdown, _pspec):
        name = self._app_names[dropdown.get_selected()]
        self._current_app = self._apps[name]
        self._reload_current()
        self._update_build_status()
        self._status.set_text(f"Switched to {name}")

    # ── Build & install ──────────────────────────────────────────────────────

    def _update_build_status(self):
        app = self._current_app
        if app.application_id:
            self._build_status.set_text(f"applicationId: {app.application_id}")
        else:
            self._build_status.set_text("applicationId not found in app/build.gradle")

    def _refresh_devices(self):
        self._device_dropdown.set_sensitive(False)

        def worker():
            devices = list_adb_devices()
            GLib.idle_add(self._on_devices_refreshed, devices)

        threading.Thread(target=worker, daemon=True).start()

    def _on_devices_refreshed(self, devices):
        self._devices = devices
        labels = [d.label for d in devices] if devices else ["(no devices detected)"]
        model = Gtk.StringList.new(labels)
        self._device_dropdown.set_model(model)
        self._device_dropdown.set_sensitive(bool(devices))
        self._device_dropdown.set_selected(0)
        self._update_build_status()
        return False

    def _selected_device(self) -> str | None:
        if not self._devices:
            return None
        idx = self._device_dropdown.get_selected()
        if idx == Gtk.INVALID_LIST_POSITION or idx >= len(self._devices):
            return None
        return self._devices[idx].serial

    def _append_log(self, line: str):
        end = self._log_buffer.get_end_iter()
        self._log_buffer.insert(end, line + "\n")
        self._log_view.scroll_to_iter(self._log_buffer.get_end_iter(), 0.0, False, 0.0, 0.0)
        return False

    def _set_build_buttons_sensitive(self, sensitive: bool):
        self._build_btn.set_sensitive(sensitive)
        self._build_install_btn.set_sensitive(sensitive)
        self._open_btn.set_sensitive(sensitive)
        # The AAB buttons share the one Gradle daemon and the one log pane, so they lock with the
        # debug buttons rather than alongside them.
        self._build_aab_btn.set_sensitive(sensitive)
        self._build_all_aab_btn.set_sensitive(sensitive)
        self._show_aab_btn.set_sensitive(sensitive)

    def _on_build_aab(self, _btn):
        self._start_aab_pipeline([self._current_app])

    def _on_build_all_aabs(self, _btn):
        self._start_aab_pipeline([self._apps[name] for name in self._app_names])

    def _on_show_aab(self, _btn):
        app = self._current_app

        def log_cb(line: str):
            GLib.idle_add(self._append_log, line)

        try:
            aab = find_aab(app.project_dir)
        except RuntimeError as e:
            self._aab_status.set_text(str(e))
            return
        reveal_in_file_manager(aab, log_cb)
        self._aab_status.set_text(f"Revealed {aab.name}")

    def _start_aab_pipeline(self, apps: list[AppProject]):
        self._set_build_buttons_sensitive(False)
        self._log_buffer.set_text("")

        def log_cb(line: str):
            GLib.idle_add(self._append_log, line)

        def worker():
            built, failed = [], []
            for app in apps:
                GLib.idle_add(
                    self._aab_status.set_text,
                    f"Building {app.name} ({len(built) + len(failed) + 1}/{len(apps)})…",
                )
                try:
                    build_aab(app.project_dir, log_cb)
                    aab = find_aab(app.project_dir)
                    if not aab_is_signed(aab):
                        # Play rejects this at upload; say so now. Not fatal to the batch.
                        log_cb(f"WARNING: {app.name} bundle is UNSIGNED (set release.* in local.properties)")
                    size_mb = aab.stat().st_size / (1024 * 1024)
                    log_cb(f"{app.name}: {aab} ({size_mb:.1f} MB)")
                    built.append(app.name)
                except Exception as e:
                    # One app failing must not abandon the rest of the batch.
                    log_cb(f"ERROR building {app.name}: {e}")
                    failed.append(app.name)
            GLib.idle_add(self._on_aab_done, built, failed)

        threading.Thread(target=worker, daemon=True).start()

    def _on_aab_done(self, built: list[str], failed: list[str]):
        self._set_build_buttons_sensitive(True)
        if failed and built:
            self._aab_status.set_text(f"Built {', '.join(built)}; failed {', '.join(failed)}")
        elif failed:
            self._aab_status.set_text(f"Failed: {', '.join(failed)} -- see log")
        else:
            self._aab_status.set_text(f"Built {', '.join(built)}.")
        return False

    def _on_build(self, _btn):
        self._start_build_pipeline(install=False)

    def _on_build_install(self, _btn):
        if self._selected_device() is None:
            self._build_status.set_text("No ADB device selected -- plug one in and hit refresh")
            return
        self._start_build_pipeline(install=True)

    def _on_open(self, _btn):
        app = self._current_app
        serial = self._selected_device()
        if serial is None:
            self._build_status.set_text("No ADB device selected -- plug one in and hit refresh")
            return
        application_id = app.application_id
        if not application_id:
            self._build_status.set_text("applicationId not found -- can't open")
            return
        self._set_build_buttons_sensitive(False)
        self._build_status.set_text(f"Opening {application_id}…")

        def log_cb(line: str):
            GLib.idle_add(self._append_log, line)

        def worker():
            try:
                launch_app(application_id, serial, log_cb)
                GLib.idle_add(self._on_open_done, None)
            except Exception as e:
                GLib.idle_add(self._on_open_done, str(e))

        threading.Thread(target=worker, daemon=True).start()

    def _on_open_done(self, error):
        self._set_build_buttons_sensitive(True)
        if error:
            self._build_status.set_text(f"Error: {error}")
        else:
            self._build_status.set_text("Opened.")
        return False

    def _start_build_pipeline(self, install: bool):
        app = self._current_app
        serial = self._selected_device() if install else None
        self._set_build_buttons_sensitive(False)
        self._log_buffer.set_text("")
        self._build_status.set_text(f"Building {app.name}…")

        def log_cb(line: str):
            GLib.idle_add(self._append_log, line)

        def worker():
            try:
                build_app(app.project_dir, log_cb)
                if install and serial is not None:
                    GLib.idle_add(self._build_status.set_text, f"Installing on {serial}…")
                    apk = find_apk(app.project_dir)
                    install_apk(apk, serial, log_cb)
                GLib.idle_add(self._on_build_done, None, install)
            except Exception as e:
                GLib.idle_add(self._on_build_done, str(e), install)

        threading.Thread(target=worker, daemon=True).start()

    def _on_build_done(self, error, install: bool):
        self._set_build_buttons_sensitive(True)
        if error:
            self._build_status.set_text(f"Error: {error}")
        elif install:
            self._build_status.set_text("Built & installed.")
        else:
            self._build_status.set_text("Build succeeded.")
        return False

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
        remove_bg = self._remove_bg_check.get_active()

        def worker():
            try:
                generate_icons(self._pending_path, self._current_app.res_dir, remove_bg=remove_bg)
                GLib.idle_add(self._on_done, None)
            except Exception as e:
                GLib.idle_add(self._on_done, str(e))

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
        res_dir = self._current_app.res_dir
        # Show the xxxhdpi square as the "current" icon
        xxxhdpi = res_dir / "mipmap-xxxhdpi" / "ic_launcher.png"
        if xxxhdpi.exists():
            pb = GdkPixbuf.Pixbuf.new_from_file_at_scale(str(xxxhdpi), 96, 96, True)
            self._current_image.set_from_pixbuf(pb)
        else:
            self._current_image.clear()

        for density, size in VARIANTS:
            sq_path  = res_dir / f"mipmap-{density}" / "ic_launcher.png"
            rnd_path = res_dir / f"mipmap-{density}" / "ic_launcher_round.png"
            sq_img, rnd_img = self._variant_images[density]
            display = min(size, 48)
            if sq_path.exists():
                pb = GdkPixbuf.Pixbuf.new_from_file_at_scale(str(sq_path), display, display, True)
                sq_img.set_from_pixbuf(pb)
            else:
                sq_img.clear()
            if rnd_path.exists():
                pb = GdkPixbuf.Pixbuf.new_from_file_at_scale(str(rnd_path), display, display, True)
                rnd_img.set_from_pixbuf(pb)
            else:
                rnd_img.clear()


def main():
    app = IconToolApp()
    sys.exit(app.run(sys.argv))


if __name__ == "__main__":
    main()

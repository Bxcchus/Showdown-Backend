use super::{AppState, find_lcu_lockfile};
use std::{
    ffi::c_void,
    mem::{size_of, zeroed},
    ptr::{null, null_mut},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    thread::{self, JoinHandle},
};
use windows_sys::Win32::{
    Foundation::{COLORREF, HWND, LPARAM, LRESULT, POINT, RECT, WPARAM},
    Graphics::Gdi::{
        BI_RGB, BITMAPINFO, BITMAPINFOHEADER, BeginPaint, CLEARTYPE_QUALITY, CLIP_DEFAULT_PRECIS,
        CreateBitmap, CreateDIBSection, CreateFontW, CreateSolidBrush, DEFAULT_CHARSET,
        DIB_RGB_COLORS, DeleteObject, DrawTextW, EndPaint, FIXED_PITCH, FW_BOLD, FW_NORMAL,
        FillRect, HBRUSH, HDC, HFONT, HGDIOBJ, InvalidateRect, OUT_DEFAULT_PRECIS, PAINTSTRUCT,
        SelectObject, SetBkMode, SetTextColor, TRANSPARENT,
    },
    System::LibraryLoader::GetModuleHandleW,
    UI::{
        Shell::{
            NIF_ICON, NIF_MESSAGE, NIF_TIP, NIM_ADD, NIM_DELETE, NOTIFYICONDATAW, Shell_NotifyIconW,
        },
        WindowsAndMessaging::{
            AppendMenuW, CS_DROPSHADOW, CS_HREDRAW, CS_VREDRAW, CreateIconIndirect,
            CreatePopupMenu, CreateWindowExW, DefWindowProcW, DestroyIcon, DestroyMenu,
            DestroyWindow, DispatchMessageW, GWLP_USERDATA, GetClientRect, GetCursorPos,
            GetMessageW, GetSystemMetrics, HTCAPTION, HTCLIENT, ICONINFO, IDC_ARROW,
            IDI_APPLICATION, LoadCursorW, LoadIconW, MF_SEPARATOR, MF_STRING, MSG, PostMessageW,
            PostQuitMessage, RegisterClassW, SM_CXSCREEN, SM_CYSCREEN, SW_HIDE, SW_MINIMIZE,
            SW_RESTORE, SW_SHOW, SetForegroundWindow, SetTimer, SetWindowLongPtrW, ShowWindow,
            TPM_BOTTOMALIGN, TPM_LEFTALIGN, TPM_RIGHTBUTTON, TrackPopupMenu, TranslateMessage,
            WINDOW_EX_STYLE, WM_APP, WM_CLOSE, WM_COMMAND, WM_DESTROY, WM_ERASEBKGND,
            WM_LBUTTONDBLCLK, WM_LBUTTONUP, WM_NCCREATE, WM_NCHITTEST, WM_NULL, WM_PAINT,
            WM_RBUTTONUP, WM_TIMER, WNDCLASSW, WS_EX_APPWINDOW, WS_POPUP, WS_VISIBLE,
        },
    },
};

const WIDTH: i32 = 500;
const HEIGHT: i32 = 310;
const TITLE_HEIGHT: i32 = 42;
const TRAY_MESSAGE: u32 = WM_APP + 7;
const TRAY_ID: u32 = 1;
const MENU_OPEN: usize = 1001;
const MENU_QUIT: usize = 1002;

struct WindowContext {
    state: AppState,
    shutdown: Arc<AtomicBool>,
    regular_font: HFONT,
    bold_font: HFONT,
}

pub(super) fn spawn(state: AppState, shutdown: Arc<AtomicBool>) -> JoinHandle<()> {
    thread::Builder::new()
        .name("showdown-watcher-ui".into())
        .spawn(move || unsafe { run(state, shutdown) })
        .expect("watcher UI thread")
}

unsafe fn run(state: AppState, shutdown: Arc<AtomicBool>) {
    let instance = unsafe { GetModuleHandleW(null()) };
    let class_name = wide("PinkwardShowdownWatcherWindow");
    let title = wide("Pinkward Watcher");
    let face = wide("Consolas");
    let regular_font = unsafe {
        CreateFontW(
            -14,
            0,
            0,
            0,
            FW_NORMAL as i32,
            0,
            0,
            0,
            DEFAULT_CHARSET.into(),
            OUT_DEFAULT_PRECIS.into(),
            CLIP_DEFAULT_PRECIS.into(),
            CLEARTYPE_QUALITY.into(),
            FIXED_PITCH.into(),
            face.as_ptr(),
        )
    };
    let bold_font = unsafe {
        CreateFontW(
            -14,
            0,
            0,
            0,
            FW_BOLD as i32,
            0,
            0,
            0,
            DEFAULT_CHARSET.into(),
            OUT_DEFAULT_PRECIS.into(),
            CLIP_DEFAULT_PRECIS.into(),
            CLEARTYPE_QUALITY.into(),
            FIXED_PITCH.into(),
            face.as_ptr(),
        )
    };
    let custom_icon = unsafe { create_app_icon() };
    let icon = if custom_icon.is_null() {
        unsafe { LoadIconW(null_mut(), IDI_APPLICATION) }
    } else {
        custom_icon
    };
    let cursor = unsafe { LoadCursorW(null_mut(), IDC_ARROW) };
    let class = WNDCLASSW {
        style: CS_HREDRAW | CS_VREDRAW | CS_DROPSHADOW,
        lpfnWndProc: Some(window_proc),
        hInstance: instance,
        hIcon: icon,
        hCursor: cursor,
        lpszClassName: class_name.as_ptr(),
        ..unsafe { zeroed() }
    };
    if unsafe { RegisterClassW(&class) } == 0 {
        shutdown.store(true, Ordering::Release);
        return;
    }

    let mut context = Box::new(WindowContext {
        state,
        shutdown: shutdown.clone(),
        regular_font,
        bold_font,
    });
    let x = (unsafe { GetSystemMetrics(SM_CXSCREEN) } - WIDTH) / 2;
    let y = (unsafe { GetSystemMetrics(SM_CYSCREEN) } - HEIGHT) / 2;
    let hwnd = unsafe {
        CreateWindowExW(
            WS_EX_APPWINDOW as WINDOW_EX_STYLE,
            class_name.as_ptr(),
            title.as_ptr(),
            WS_POPUP | WS_VISIBLE,
            x,
            y,
            WIDTH,
            HEIGHT,
            null_mut(),
            null_mut(),
            instance,
            context.as_mut() as *mut WindowContext as *const c_void,
        )
    };
    if hwnd.is_null() {
        shutdown.store(true, Ordering::Release);
        unsafe {
            DeleteObject(regular_font as HGDIOBJ);
            DeleteObject(bold_font as HGDIOBJ);
        }
        return;
    }

    let mut tray: NOTIFYICONDATAW = unsafe { zeroed() };
    tray.cbSize = size_of::<NOTIFYICONDATAW>() as u32;
    tray.hWnd = hwnd;
    tray.uID = TRAY_ID;
    tray.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
    tray.uCallbackMessage = TRAY_MESSAGE;
    tray.hIcon = icon;
    copy_wide(&mut tray.szTip, "Pinkward Watcher — actif");
    unsafe {
        Shell_NotifyIconW(NIM_ADD, &tray);
        SetTimer(hwnd, 1, 500, None);
        ShowWindow(hwnd, SW_SHOW);
    }

    let mut message: MSG = unsafe { zeroed() };
    while unsafe { GetMessageW(&mut message, null_mut(), 0, 0) } > 0 {
        unsafe {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }

    unsafe {
        Shell_NotifyIconW(NIM_DELETE, &tray);
        DeleteObject(regular_font as HGDIOBJ);
        DeleteObject(bold_font as HGDIOBJ);
        if !custom_icon.is_null() {
            DestroyIcon(custom_icon);
        }
    }
    shutdown.store(true, Ordering::Release);
}

unsafe fn create_app_icon() -> windows_sys::Win32::UI::WindowsAndMessaging::HICON {
    const SIZE: usize = 32;
    let info = BITMAPINFO {
        bmiHeader: BITMAPINFOHEADER {
            biSize: size_of::<BITMAPINFOHEADER>() as u32,
            biWidth: SIZE as i32,
            biHeight: -(SIZE as i32),
            biPlanes: 1,
            biBitCount: 32,
            biCompression: BI_RGB,
            biSizeImage: (SIZE * SIZE * 4) as u32,
            ..BITMAPINFOHEADER::default()
        },
        ..BITMAPINFO::default()
    };
    let mut bits: *mut c_void = null_mut();
    let color =
        unsafe { CreateDIBSection(null_mut(), &info, DIB_RGB_COLORS, &mut bits, null_mut(), 0) };
    if color.is_null() || bits.is_null() {
        return null_mut();
    }

    let pixels = unsafe { std::slice::from_raw_parts_mut(bits as *mut u32, SIZE * SIZE) };
    let bolt = [(18, 4), (9, 18), (15, 18), (12, 28), (24, 13), (18, 13)];
    for y in 0..SIZE as i32 {
        for x in 0..SIZE as i32 {
            let dx = x - 16;
            let dy = y - 16;
            pixels[y as usize * SIZE + x as usize] = if point_in_polygon(x, y, &bolt) {
                0xfff8f3f7
            } else if dx * dx + dy * dy <= 14 * 14 {
                0xffdf5a9d
            } else {
                0x00000000
            };
        }
    }

    let mask_bits = [0u8; SIZE * SIZE / 8];
    let mask = unsafe {
        CreateBitmap(
            SIZE as i32,
            SIZE as i32,
            1,
            1,
            mask_bits.as_ptr() as *const c_void,
        )
    };
    if mask.is_null() {
        unsafe { DeleteObject(color as HGDIOBJ) };
        return null_mut();
    }
    let icon_info = ICONINFO {
        fIcon: 1,
        hbmMask: mask,
        hbmColor: color,
        ..ICONINFO::default()
    };
    let icon = unsafe { CreateIconIndirect(&icon_info) };
    unsafe {
        DeleteObject(color as HGDIOBJ);
        DeleteObject(mask as HGDIOBJ);
    }
    icon
}

fn point_in_polygon(x: i32, y: i32, points: &[(i32, i32)]) -> bool {
    let mut inside = false;
    let mut previous = points.len() - 1;
    for current in 0..points.len() {
        let (xi, yi) = points[current];
        let (xj, yj) = points[previous];
        if (yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi {
            inside = !inside;
        }
        previous = current;
    }
    inside
}

unsafe extern "system" fn window_proc(
    hwnd: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    if message == WM_NCCREATE {
        let create = lparam as *const windows_sys::Win32::UI::WindowsAndMessaging::CREATESTRUCTW;
        if !create.is_null() {
            unsafe {
                SetWindowLongPtrW(hwnd, GWLP_USERDATA, (*create).lpCreateParams as isize);
            }
        }
    }
    let context = unsafe {
        let raw =
            windows_sys::Win32::UI::WindowsAndMessaging::GetWindowLongPtrW(hwnd, GWLP_USERDATA);
        (raw != 0).then(|| &*(raw as *const WindowContext))
    };

    match message {
        WM_CLOSE => {
            unsafe {
                ShowWindow(hwnd, SW_HIDE);
            }
            0
        }
        WM_DESTROY => {
            if let Some(context) = context {
                context.shutdown.store(true, Ordering::Release);
            }
            unsafe {
                PostQuitMessage(0);
            }
            0
        }
        WM_TIMER => {
            if context.is_some_and(|value| value.shutdown.load(Ordering::Acquire)) {
                unsafe {
                    DestroyWindow(hwnd);
                }
            } else {
                unsafe {
                    InvalidateRect(hwnd, null(), 0);
                }
            }
            0
        }
        WM_ERASEBKGND => 1,
        WM_PAINT => {
            if let Some(context) = context {
                unsafe {
                    paint(hwnd, context);
                }
            }
            0
        }
        WM_NCHITTEST => {
            let x = signed_low_word(lparam) as i32;
            let y = signed_high_word(lparam) as i32;
            let mut point = POINT { x, y };
            unsafe {
                windows_sys::Win32::Graphics::Gdi::ScreenToClient(hwnd, &mut point);
            }
            if point.y < TITLE_HEIGHT && point.x < WIDTH - 82 {
                HTCAPTION as LRESULT
            } else {
                HTCLIENT as LRESULT
            }
        }
        WM_LBUTTONUP => {
            let x = signed_low_word(lparam) as i32;
            let y = signed_high_word(lparam) as i32;
            if y < TITLE_HEIGHT {
                if x >= WIDTH - 42 {
                    unsafe {
                        ShowWindow(hwnd, SW_HIDE);
                    }
                } else if x >= WIDTH - 82 {
                    unsafe {
                        ShowWindow(hwnd, SW_MINIMIZE);
                    }
                }
            }
            0
        }
        WM_COMMAND => {
            match wparam & 0xffff {
                MENU_OPEN => unsafe {
                    restore(hwnd);
                },
                MENU_QUIT => unsafe {
                    DestroyWindow(hwnd);
                },
                _ => {}
            }
            0
        }
        TRAY_MESSAGE => {
            match lparam as u32 {
                WM_LBUTTONDBLCLK => unsafe {
                    restore(hwnd);
                },
                WM_RBUTTONUP => unsafe {
                    show_tray_menu(hwnd);
                },
                _ => {}
            }
            0
        }
        _ => unsafe { DefWindowProcW(hwnd, message, wparam, lparam) },
    }
}

unsafe fn restore(hwnd: HWND) {
    unsafe {
        ShowWindow(hwnd, SW_RESTORE);
        ShowWindow(hwnd, SW_SHOW);
        SetForegroundWindow(hwnd);
    }
}

unsafe fn show_tray_menu(hwnd: HWND) {
    let menu = unsafe { CreatePopupMenu() };
    if menu.is_null() {
        return;
    }
    let open = wide("Ouvrir Pinkward Watcher");
    let quit = wide("Quitter complètement");
    unsafe {
        AppendMenuW(menu, MF_STRING, MENU_OPEN, open.as_ptr());
        AppendMenuW(menu, MF_SEPARATOR, 0, null());
        AppendMenuW(menu, MF_STRING, MENU_QUIT, quit.as_ptr());
        let mut point = POINT::default();
        GetCursorPos(&mut point);
        SetForegroundWindow(hwnd);
        TrackPopupMenu(
            menu,
            TPM_LEFTALIGN | TPM_BOTTOMALIGN | TPM_RIGHTBUTTON,
            point.x,
            point.y,
            0,
            hwnd,
            null(),
        );
        PostMessageW(hwnd, WM_NULL, 0, 0);
        DestroyMenu(menu);
    }
}

unsafe fn paint(hwnd: HWND, context: &WindowContext) {
    let mut paint: PAINTSTRUCT = unsafe { zeroed() };
    let dc = unsafe { BeginPaint(hwnd, &mut paint) };
    let mut client = RECT::default();
    unsafe {
        GetClientRect(hwnd, &mut client);
    }
    fill(dc, &client, rgb(5, 9, 12));
    fill(
        dc,
        &RECT {
            left: 0,
            top: TITLE_HEIGHT - 1,
            right: WIDTH,
            bottom: TITLE_HEIGHT,
        },
        rgb(34, 48, 56),
    );
    unsafe {
        SetBkMode(dc, TRANSPARENT as i32);
    }

    text(
        dc,
        context.regular_font,
        rgb(91, 139, 157),
        16,
        0,
        270,
        TITLE_HEIGHT,
        "PINKWARD / WATCHER",
    );
    text(
        dc,
        context.regular_font,
        rgb(105, 135, 145),
        WIDTH - 82,
        0,
        WIDTH - 42,
        TITLE_HEIGHT,
        "—",
    );
    text(
        dc,
        context.regular_font,
        rgb(105, 135, 145),
        WIDTH - 42,
        0,
        WIDTH,
        TITLE_HEIGHT,
        "×",
    );

    text(
        dc,
        context.regular_font,
        rgb(72, 130, 151),
        27,
        59,
        160,
        81,
        "STATUS",
    );
    let dot = RECT {
        left: 27,
        top: 100,
        right: 35,
        bottom: 108,
    };
    fill(dc, &dot, rgb(48, 207, 132));
    text(
        dc,
        context.bold_font,
        rgb(48, 207, 132),
        43,
        91,
        270,
        118,
        "WATCHER ONLINE",
    );

    let job = context.state.job.blocking_read().clone();
    row(
        dc,
        context,
        128,
        "LOCAL ENDPOINT",
        "127.0.0.1:43991",
        rgb(238, 244, 246),
    );
    let league_connected = league_connected();
    row(
        dc,
        context,
        156,
        "LEAGUE CLIENT",
        if league_connected {
            "CONNECTED"
        } else {
            "DISCONNECTED"
        },
        if league_connected {
            rgb(238, 244, 246)
        } else {
            rgb(232, 92, 119)
        },
    );
    row(
        dc,
        context,
        184,
        "ACTIVE DUEL",
        display_state(&job.state),
        state_color(&job.state),
    );

    let detail = job.detail.as_deref().unwrap_or_else(|| {
        if job.state == "IDLE" {
            "Ready to receive a Pinkward duel."
        } else {
            "Watcher state updated."
        }
    });
    text(
        dc,
        context.regular_font,
        state_color(&job.state),
        27,
        213,
        WIDTH - 24,
        252,
        &format!("> {detail}"),
    );
    text(
        dc,
        context.regular_font,
        rgb(53, 82, 94),
        27,
        267,
        WIDTH - 24,
        295,
        "Close hides to tray · right-click the icon to quit",
    );

    unsafe {
        EndPaint(hwnd, &paint);
    }
}

fn row(dc: HDC, context: &WindowContext, top: i32, label: &str, value: &str, color: COLORREF) {
    text(
        dc,
        context.regular_font,
        rgb(82, 125, 142),
        27,
        top,
        170,
        top + 23,
        label,
    );
    text(
        dc,
        context.bold_font,
        color,
        177,
        top,
        WIDTH - 25,
        top + 23,
        value,
    );
}

#[allow(clippy::too_many_arguments)]
fn text(
    dc: HDC,
    font: HFONT,
    color: COLORREF,
    left: i32,
    top: i32,
    right: i32,
    bottom: i32,
    value: &str,
) {
    let value = wide(value);
    let mut rect = RECT {
        left,
        top,
        right,
        bottom,
    };
    unsafe {
        SelectObject(dc, font as HGDIOBJ);
        SetTextColor(dc, color);
        DrawTextW(dc, value.as_ptr(), -1, &mut rect, 0x0020 | 0x0004);
    }
}

fn fill(dc: HDC, rect: &RECT, color: COLORREF) {
    unsafe {
        let brush: HBRUSH = CreateSolidBrush(color);
        FillRect(dc, rect, brush);
        DeleteObject(brush as HGDIOBJ);
    }
}

fn display_state(state: &str) -> &str {
    match state {
        "IDLE" => "WAITING",
        "STARTING" => "STARTING",
        "LCU_CONNECTED" => "LEAGUE CONNECTED",
        "LOBBY_CREATED" => "LOBBY CREATED",
        "INVITE_SENT" => "INVITE SENT",
        "JOINING" => "JOINING",
        "JOINED" => "JOINED",
        "BOTH_PRESENT" => "PLAYERS READY",
        "CHAMP_SELECT_STARTED" => "CHAMP SELECT",
        "IN_GAME" => "IN GAME",
        "COMPLETED" => "COMPLETED",
        "ERROR" => "ERROR",
        other => other,
    }
}

fn state_color(state: &str) -> COLORREF {
    match state {
        "ERROR" => rgb(232, 92, 119),
        "COMPLETED" => rgb(48, 207, 132),
        _ => rgb(232, 173, 45),
    }
}

fn league_connected() -> bool {
    (std::env::var("SHOWDOWN_LCU_PORT").is_ok() && std::env::var("SHOWDOWN_LCU_TOKEN").is_ok())
        || find_lcu_lockfile().is_ok()
}

const fn rgb(red: u8, green: u8, blue: u8) -> COLORREF {
    red as u32 | ((green as u32) << 8) | ((blue as u32) << 16)
}

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(std::iter::once(0)).collect()
}

fn copy_wide<const N: usize>(target: &mut [u16; N], value: &str) {
    for (destination, source) in target
        .iter_mut()
        .zip(value.encode_utf16().chain(std::iter::once(0)))
    {
        *destination = source;
    }
}

fn signed_low_word(value: LPARAM) -> i16 {
    (value as u16) as i16
}
fn signed_high_word(value: LPARAM) -> i16 {
    ((value >> 16) as u16) as i16
}

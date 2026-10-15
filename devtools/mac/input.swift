import Foundation
import CoreGraphics
// usage: mouse move x y | click x y | drag x1 y1 x2 y2 | rclick x y | scroll dy | key code [flags] | type text
let a = CommandLine.arguments
func post(_ t: CGEventType, _ x: Double, _ y: Double, _ b: CGMouseButton = .left) {
  let e = CGEvent(mouseEventSource: nil, mouseType: t, mouseCursorPosition: CGPoint(x: x, y: y), mouseButton: b)
  e?.post(tap: .cghidEventTap)
}
func D(_ i: Int) -> Double { Double(a[i])! }
switch a[1] {
case "move": post(.mouseMoved, D(2), D(3))
case "click":
  post(.mouseMoved, D(2), D(3)); usleep(80000)
  post(.leftMouseDown, D(2), D(3)); usleep(60000); post(.leftMouseUp, D(2), D(3))
case "rclick":
  post(.mouseMoved, D(2), D(3)); usleep(80000)
  post(.rightMouseDown, D(2), D(3), .right); usleep(60000); post(.rightMouseUp, D(2), D(3), .right)
case "drag":
  post(.mouseMoved, D(2), D(3)); usleep(80000)
  post(.leftMouseDown, D(2), D(3)); usleep(80000)
  let n = 20
  for i in 1...n { let x = D(2)+(D(4)-D(2))*Double(i)/Double(n); let y = D(3)+(D(5)-D(3))*Double(i)/Double(n); post(.leftMouseDragged, x, y); usleep(20000) }
  usleep(60000); post(.leftMouseUp, D(4), D(5))
case "scroll":
  let e = CGEvent(scrollWheelEvent2Source: nil, units: .line, wheelCount: 1, wheel1: Int32(a[2])!, wheel2: 0, wheel3: 0); e?.post(tap: .cghidEventTap)
case "key":
  let c = CGKeyCode(UInt16(a[2])!)
  let d = CGEvent(keyboardEventSource: nil, virtualKey: c, keyDown: true); let u = CGEvent(keyboardEventSource: nil, virtualKey: c, keyDown: false)
  if a.count > 3 { let f = a[3]; var fl = CGEventFlags(); if f.contains("shift") { fl.insert(.maskShift) }; if f.contains("cmd") { fl.insert(.maskCommand) }; if f.contains("ctrl") { fl.insert(.maskControl) }; d?.flags = fl; u?.flags = fl }
  d?.post(tap: .cghidEventTap); usleep(50000); u?.post(tap: .cghidEventTap)
case "type":
  for ch in a[2].utf16 { var c = ch; let d = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: true); d?.keyboardSetUnicodeString(stringLength: 1, unicodeString: &c); d?.post(tap: .cghidEventTap); let u = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: false); u?.keyboardSetUnicodeString(stringLength: 1, unicodeString: &c); u?.post(tap: .cghidEventTap); usleep(40000) }
case "chord":
  // chord <modifierKeyCode> <keyCode>: holds a real modifier key down around the key press (flags alone are ignored by GLFW)
  let m = CGKeyCode(UInt16(a[2])!), k = CGKeyCode(UInt16(a[3])!)
  let flag: CGEventFlags = (m == 55 || m == 54) ? .maskCommand : (m == 59 ? .maskControl : (m == 56 ? .maskShift : .maskAlternate))
  let md = CGEvent(keyboardEventSource: nil, virtualKey: m, keyDown: true); md?.flags = flag; md?.post(tap: .cghidEventTap); usleep(120000)
  let kd = CGEvent(keyboardEventSource: nil, virtualKey: k, keyDown: true); kd?.flags = flag; kd?.post(tap: .cghidEventTap); usleep(80000)
  let ku = CGEvent(keyboardEventSource: nil, virtualKey: k, keyDown: false); ku?.flags = flag; ku?.post(tap: .cghidEventTap); usleep(80000)
  let mu = CGEvent(keyboardEventSource: nil, virtualKey: m, keyDown: false); mu?.post(tap: .cghidEventTap)
default: break
}

import { createRoot } from "react-dom/client";
import "../i18n";
import { ThemeProvider } from "../contexts/ThemeContext";
import BackendReadyGate from "./BackendReadyGate";
import CloseWindowPrompt from "./CloseWindowPrompt";

// Native drag-drop interception is disabled on the window, so OS file
// drags arrive as HTML5 drag events. Block the default "navigate to
// dropped file" behavior on this bootstrap page; the console app installs
// its own guard in main.tsx after navigation.
window.addEventListener("dragover", (e) => e.preventDefault());
window.addEventListener("drop", (e) => e.preventDefault());

// Surface any render-time exception in the shell log — a silent throw here
// leaves the window blank with no other diagnostic trail.
window.addEventListener("error", (event) => {
  console.error("[bootstrap] uncaught error:", event.message, event.filename, event.lineno);
});
window.addEventListener("unhandledrejection", (event) => {
  console.error("[bootstrap] unhandled rejection:", String(event.reason));
});

createRoot(document.getElementById("root")!).render(
  <ThemeProvider>
    <CloseWindowPrompt />
    <BackendReadyGate>{null}</BackendReadyGate>
  </ThemeProvider>,
);

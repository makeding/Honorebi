import { createApp } from "vue";
import App from "./App.vue";
import ScreensaverApp from "./ScreensaverApp.vue";
import { isScreensaverPath } from "./screensaver";
import "./style.css";

const RootComponent = isScreensaverPath(
  window.location.pathname,
  window.location.search,
)
  ? ScreensaverApp
  : App;

createApp(RootComponent).mount("#app");

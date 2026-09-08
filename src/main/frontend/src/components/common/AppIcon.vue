<script setup>
import { computed } from "vue";

const props = defineProps({
  name: {
    type: String,
    required: true
  },
  size: {
    type: [Number, String],
    default: 18
  },
  strokeWidth: {
    type: [Number, String],
    default: 2
  }
});

const icon = computed(() => {
  const icons = {
    panelLeft: [["rect", { x: "3", y: "3", width: "18", height: "18", rx: "3" }], ["path", { d: "M9 3v18" }]],
    search: [["circle", { cx: "10.5", cy: "10.5", r: "6.5" }], ["path", { d: "m16 16 5 5" }]],
    bot: [
      ["path", { d: "M12 8V4" }],
      ["rect", { x: "5", y: "8", width: "14", height: "10", rx: "3" }],
      ["path", { d: "M8 18v2" }],
      ["path", { d: "M16 18v2" }],
      ["path", { d: "M9 13h.01" }],
      ["path", { d: "M15 13h.01" }]
    ],
    brain: [
      ["path", { d: "M9 4a3 3 0 0 0-3 3v1a3 3 0 0 0 0 6v1a3 3 0 0 0 3 3" }],
      ["path", { d: "M15 4a3 3 0 0 1 3 3v1a3 3 0 0 1 0 6v1a3 3 0 0 1-3 3" }],
      ["path", { d: "M9 4v16" }],
      ["path", { d: "M15 4v16" }],
      ["path", { d: "M9 8H7" }],
      ["path", { d: "M15 8h2" }],
      ["path", { d: "M9 14H6" }],
      ["path", { d: "M15 14h3" }]
    ],
    chevronDown: [
      ["path", { d: "m6 9 6 6 6-6" }]
    ],
    copy: [
      ["rect", { x: "9", y: "9", width: "11", height: "11", rx: "2" }],
      ["path", { d: "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" }]
    ],
    database: [
      ["ellipse", { cx: "12", cy: "5", rx: "8", ry: "3" }],
      ["path", { d: "M4 5v6c0 1.7 3.6 3 8 3s8-1.3 8-3V5" }],
      ["path", { d: "M4 11v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" }]
    ],
    download: [
      ["path", { d: "M12 3v12" }],
      ["path", { d: "m7 10 5 5 5-5" }],
      ["path", { d: "M5 21h14" }]
    ],
    filePlus: [
      ["path", { d: "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" }],
      ["path", { d: "M14 2v6h6" }],
      ["path", { d: "M12 18v-6" }],
      ["path", { d: "M9 15h6" }]
    ],
    fileText: [
      ["path", { d: "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" }],
      ["path", { d: "M14 2v6h6" }],
      ["path", { d: "M16 13H8" }],
      ["path", { d: "M16 17H8" }],
      ["path", { d: "M10 9H8" }]
    ],
    folderOpen: [
      ["path", { d: "M6 17 8 9h12l-2 8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4l2 3h7a2 2 0 0 1 2 2v1" }]
    ],
    messageCircle: [
      ["path", { d: "M21 11.5a8.4 8.4 0 0 1-9 8.4 8.5 8.5 0 0 1-4-.9L3 21l1.8-4.6a8.4 8.4 0 1 1 16.2-4.9Z" }]
    ],
    paperclip: [
      ["path", { d: "m21.4 11.6-8.5 8.5a5.5 5.5 0 0 1-7.8-7.8l9.2-9.2a3.7 3.7 0 0 1 5.2 5.2l-9.2 9.2a1.8 1.8 0 0 1-2.6-2.6l8.5-8.5" }]
    ],
    plus: [
      ["path", { d: "M12 5v14" }],
      ["path", { d: "M5 12h14" }]
    ],
    refreshCw: [
      ["path", { d: "M21 12a9 9 0 0 1-15.3 6.4L3 16" }],
      ["path", { d: "M3 21v-5h5" }],
      ["path", { d: "M3 12a9 9 0 0 1 15.3-6.4L21 8" }],
      ["path", { d: "M16 8h5V3" }]
    ],
    rotateCcw: [
      ["path", { d: "M3 12a9 9 0 1 0 3-6.7L3 8" }],
      ["path", { d: "M3 3v5h5" }]
    ],
    save: [
      ["path", { d: "M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2Z" }],
      ["path", { d: "M17 21v-8H7v8" }],
      ["path", { d: "M7 3v5h8" }]
    ],
    sendHorizontal: [
      ["path", { d: "m3 3 18 9-18 9 4-9Z" }],
      ["path", { d: "M7 12h14" }]
    ],
    settings: [
      ["path", { d: "M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z" }],
      ["path", { d: "M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.9L4.2 7a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3h.1a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5h.1a1.7 1.7 0 0 0 1.9-.3l.1-.1A2 2 0 1 1 20 7l-.1.1a1.7 1.7 0 0 0-.3 1.9v.1a1.7 1.7 0 0 0 1.5 1h.1a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.7 1Z" }]
    ],
    sun: [
      ["circle", { cx: "12", cy: "12", r: "4" }],
      ["path", { d: "M12 2v2" }],
      ["path", { d: "M12 20v2" }],
      ["path", { d: "m4.93 4.93 1.42 1.42" }],
      ["path", { d: "m17.66 17.66 1.41 1.41" }],
      ["path", { d: "M2 12h2" }],
      ["path", { d: "M20 12h2" }],
      ["path", { d: "m6.34 17.66-1.41 1.41" }],
      ["path", { d: "m19.07 4.93-1.41 1.41" }]
    ],
    moon: [
      ["path", { d: "M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8Z" }]
    ],
    square: [
      ["rect", { x: "6", y: "6", width: "12", height: "12", rx: "2" }]
    ],
    trash2: [
      ["path", { d: "M3 6h18" }],
      ["path", { d: "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" }],
      ["path", { d: "M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" }],
      ["path", { d: "M10 11v6" }],
      ["path", { d: "M14 11v6" }]
    ],
    upload: [
      ["path", { d: "M12 21V9" }],
      ["path", { d: "m17 14-5-5-5 5" }],
      ["path", { d: "M5 3h14" }]
    ],
    x: [
      ["path", { d: "M18 6 6 18" }],
      ["path", { d: "m6 6 12 12" }]
    ]
  };
  return icons[props.name] || icons.fileText;
});
</script>

<template>
  <svg
    class="app-icon"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    aria-hidden="true"
    focusable="false"
  >
    <component
      :is="part[0]"
      v-for="(part, index) in icon"
      :key="`${name}-${index}`"
      v-bind="part[1]"
      stroke="currentColor"
      :stroke-width="strokeWidth"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
  </svg>
</template>

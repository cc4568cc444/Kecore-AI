import { nextTick, onBeforeUnmount, reactive, ref } from "vue";

export function useFloatingSelect(root = ref(null)) {
  const open = ref(false);
  const placement = ref("bottom");
  const align = ref("left");
  const style = reactive({
    "--custom-select-menu-max-height": "280px"
  });

  function close() {
    open.value = false;
  }

  function updatePlacement() {
    const wrap = root.value;
    const button = wrap?.querySelector(".custom-select-button");
    const menu = wrap?.querySelector(".custom-select-menu");
    if (!wrap || !button || !menu) {
      return;
    }

    const buttonRect = button.getBoundingClientRect();
    const boundary = {
      top: 8,
      right: window.innerWidth - 8,
      bottom: window.innerHeight - 8,
      left: 8
    };
    const spaceBelow = Math.max(0, boundary.bottom - buttonRect.bottom - 8);
    const spaceAbove = Math.max(0, buttonRect.top - boundary.top - 8);
    const menuHeight = Math.max(menu.scrollHeight || 0, 120);
    const nextPlacement = spaceAbove > spaceBelow && spaceAbove >= Math.min(menuHeight, spaceBelow)
      ? "top"
      : "bottom";
    const availableSpace = nextPlacement === "top" ? spaceAbove : spaceBelow;
    const menuWidth = Math.max(menu.scrollWidth || 0, buttonRect.width, 180);
    const nextAlign = buttonRect.left + menuWidth > boundary.right && buttonRect.right - menuWidth >= boundary.left
      ? "right"
      : "left";

    placement.value = nextPlacement;
    align.value = nextAlign;
    style["--custom-select-menu-max-height"] = `${Math.max(120, Math.min(280, availableSpace))}px`;
  }

  function toggle() {
    open.value = !open.value;
    if (open.value) {
      nextTick(updatePlacement);
    }
  }

  function handleOutsideClick(event) {
    if (open.value && root.value && !root.value.contains(event.target)) {
      close();
    }
  }

  function handleViewportChange(event) {
    const target = event?.target;
    if (event?.type === "scroll" && target?.nodeType && root.value?.contains(target)) {
      return;
    }
    if (open.value) {
      updatePlacement();
    }
  }

  window.addEventListener("click", handleOutsideClick, true);
  window.addEventListener("resize", handleViewportChange);
  window.addEventListener("scroll", handleViewportChange, true);

  onBeforeUnmount(() => {
    window.removeEventListener("click", handleOutsideClick, true);
    window.removeEventListener("resize", handleViewportChange);
    window.removeEventListener("scroll", handleViewportChange, true);
  });

  return {
    align,
    close,
    open,
    placement,
    root,
    style,
    toggle,
    updatePlacement
  };
}

<script setup>
import { computed, nextTick, ref, watch } from "vue";
import AppIcon from "./AppIcon.vue";
import { useFloatingSelect } from "../../composables/useFloatingSelect";

const props = defineProps({
  disabled: {
    type: Boolean,
    default: false
  },
  id: {
    type: String,
    required: true
  },
  modelValue: {
    type: [String, Number, Boolean],
    default: ""
  },
  options: {
    type: Array,
    default: () => []
  },
  placeholder: {
    type: String,
    default: ""
  },
  title: {
    type: String,
    default: ""
  }
});

const emit = defineEmits(["change", "update:modelValue"]);
const root = ref(null);
const {
  align,
  close,
  open,
  placement,
  style,
  toggle,
  updatePlacement
} = useFloatingSelect(root);

const normalizedOptions = computed(() => props.options.map((option) => {
  if (option && typeof option === "object") {
    return {
      disabled: Boolean(option.disabled),
      label: option.label ?? String(option.value ?? ""),
      title: option.title || "",
      value: option.value ?? "",
      buttonLabel: option.buttonLabel ?? option.shortLabel ?? option.label
    };
  }
  return {
    disabled: false,
    label: String(option ?? ""),
    title: "",
    value: option ?? "",
    buttonLabel: String(option ?? "")
  };
}));

const selectedOption = computed(() => normalizedOptions.value.find((option) =>
  String(option.value) === String(props.modelValue ?? "")
));

const displayLabel = computed(() =>
  selectedOption.value?.buttonLabel || selectedOption.value?.label || props.placeholder || normalizedOptions.value[0]?.label || ""
);

function setOpen(value) {
  if (props.disabled) {
    return;
  }
  open.value = value;
  if (value) {
    nextTick(updatePlacement);
  }
}

function chooseOption(option) {
  if (props.disabled || option.disabled) {
    return;
  }
  emit("update:modelValue", option.value);
  emit("change", option.value);
  close();
}

function handleNativeChange(event) {
  emit("update:modelValue", event.target.value);
  emit("change", event.target.value);
}

watch(() => [props.modelValue, normalizedOptions.value.length], () => {
  if (open.value) {
    nextTick(updatePlacement);
  }
});
</script>

<template>
  <div
    ref="root"
    class="custom-select"
    :class="[`custom-select-${id}`, { 'is-open': open, 'is-disabled': disabled }]"
    :data-placement="placement"
    :data-align="align"
    :style="style"
  >
    <select
      :id="id"
      :value="modelValue ?? ''"
      :disabled="disabled"
      aria-hidden="true"
      tabindex="-1"
      @change="handleNativeChange"
    >
      <option
        v-for="option in normalizedOptions"
        :key="`${id}-${String(option.value)}`"
        :value="option.value"
        :disabled="option.disabled"
      >{{ option.label }}</option>
    </select>
    <button
      class="custom-select-button"
      type="button"
      aria-haspopup="listbox"
      :aria-controls="`${id}CustomMenu`"
      :aria-expanded="open ? 'true' : 'false'"
      :disabled="disabled"
      :title="title"
      @click.stop="toggle"
      @keydown.down.prevent="setOpen(true)"
      @keydown.enter.prevent="setOpen(!open)"
      @keydown.esc.prevent="close"
    >
      <span class="custom-select-value">{{ displayLabel }}</span>
      <AppIcon class="custom-select-arrow" name="chevronDown" :size="18" />
    </button>
    <div :id="`${id}CustomMenu`" class="custom-select-menu" role="listbox">
      <button
        v-for="option in normalizedOptions"
        :key="`${id}-custom-${String(option.value)}`"
        class="custom-select-option"
        type="button"
        role="option"
        :aria-selected="String(option.value) === String(modelValue ?? '') ? 'true' : 'false'"
        :disabled="option.disabled"
        :title="option.title || option.label"
        @click="chooseOption(option)"
      >{{ option.label }}</button>
    </div>
  </div>
</template>

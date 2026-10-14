#!/usr/bin/env python3
"""
Applies the Hermes (Hermes-llama-android) ggml-vulkan patches to a llama.cpp
checkout, ported verbatim from clyxhub/Hermes-llama-android CMakeLists.txt.

Why: on Android (notably Adreno 6xx drivers), the Vulkan-Hpp default dispatcher
is initialised for the instance only, so core Vulkan 1.2 *device-level* entry
points (e.g. vkGetBufferDeviceAddress) stay NULL and the first call SIGSEGVs.
Additionally ggml_backend_vk_get_device_memory() can hit NULL
vkGetPhysicalDeviceMemoryProperties2 slots on this driver and crash model load.

Idempotent: skips if the HERMES-PATCH markers are already present.
Exits 1 only on hard failures (missing file); a missing anchor is a warning
(llama.cpp revisions may drift) — the build continues unpatched.
"""
import sys

MARK1 = "HERMES-PATCH(vulkan-device-dispatcher)"
ANCHOR1 = "device->device = device->physical_device.createDevice(device_create_info);"
MARK2 = "HERMES-PATCH(vulkan-device-memory)"
ANCHOR2 = "vkdev.getMemoryProperties2(&memprops);"

INSERT1 = ANCHOR1 + "\n\n" + """        // HERMES-PATCH(vulkan-device-dispatcher): the Vulkan-Hpp default dispatcher is
        // initialised for the instance only. On Android the loader does not resolve
        // every device-level entry point through vkGetInstanceProcAddr() (e.g. core
        // Vulkan 1.2 device commands such as vkGetBufferDeviceAddress), leaving those
        // DispatchLoaderDynamic slots NULL and the first call jumps to 0x0.
        //
        // init(device) resolves every slot through vkGetDeviceProcAddr(), which returns
        // NULL for instance/physical-device level commands. Restore those afterwards.
        VULKAN_HPP_DEFAULT_DISPATCHER.init(device->device);
        {
            PFN_vkGetInstanceProcAddr hermes_gipa = VULKAN_HPP_DEFAULT_DISPATCHER.vkGetInstanceProcAddr;
            VkInstance hermes_inst = (VkInstance) vk_instance.instance;
            if (hermes_gipa != nullptr) {
                VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceProperties =
                    (PFN_vkGetPhysicalDeviceProperties) hermes_gipa(hermes_inst, "vkGetPhysicalDeviceProperties");
                VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceMemoryProperties =
                    (PFN_vkGetPhysicalDeviceMemoryProperties) hermes_gipa(hermes_inst, "vkGetPhysicalDeviceMemoryProperties");
                VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceMemoryProperties2 =
                    (PFN_vkGetPhysicalDeviceMemoryProperties2) hermes_gipa(hermes_inst, "vkGetPhysicalDeviceMemoryProperties2");
                VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceFeatures2 =
                    (PFN_vkGetPhysicalDeviceFeatures2) hermes_gipa(hermes_inst, "vkGetPhysicalDeviceFeatures2");
                VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceQueueFamilyProperties =
                    (PFN_vkGetPhysicalDeviceQueueFamilyProperties) hermes_gipa(hermes_inst, "vkGetPhysicalDeviceQueueFamilyProperties");
                VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceFormatProperties =
                    (PFN_vkGetPhysicalDeviceFormatProperties) hermes_gipa(hermes_inst, "vkGetPhysicalDeviceFormatProperties");
            }
        }

        GGML_LOG_INFO("HermesLlama-vk: dispatcher ready (createBuffer=%d bda=%d memProps=%d memProps2=%d props=%d)\\n",
                      (int) (VULKAN_HPP_DEFAULT_DISPATCHER.vkCreateBuffer != nullptr),
                      (int) (VULKAN_HPP_DEFAULT_DISPATCHER.vkGetBufferDeviceAddress != nullptr),
                      (int) (VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceMemoryProperties != nullptr),
                      (int) (VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceMemoryProperties2 != nullptr),
                      (int) (VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceProperties != nullptr));
        if (device->buffer_device_address && VULKAN_HPP_DEFAULT_DISPATCHER.vkGetBufferDeviceAddress == nullptr) {
            GGML_LOG_WARN("HermesLlama-vk: vkGetBufferDeviceAddress unresolved - disabling buffer_device_address\\n");
            device->buffer_device_address = false;
        }"""

INSERT2 = """        // HERMES-PATCH(vulkan-device-memory): never call through a NULL slot.
        if (VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceMemoryProperties2 != nullptr) {
            vkdev.getMemoryProperties2(&memprops);
        } else if (VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceMemoryProperties != nullptr) {
            GGML_LOG_WARN("HermesLlama-vk: memoryProperties2 unresolved - using 1.0 fallback (memory budget disabled)\\n");
            memprops.pNext = nullptr;
            memprops.memoryProperties = vkdev.getMemoryProperties();
        } else {
            GGML_LOG_WARN("HermesLlama-vk: no memory query available - reporting unknown memory\\n");
            memprops.pNext = nullptr;
            memprops.memoryProperties.memoryHeapCount = 0;
        }"""


def apply(path: str, marker: str, anchor: str, insert: str, name: str) -> None:
    with open(path, "r", encoding="utf-8") as f:
        txt = f.read()
    if marker in txt:
        print(f"patch[{name}]: already present — skipping")
        return
    if anchor not in txt:
        print(f"patch[{name}]: WARNING anchor not found in {path} — NOT applied")
        return
    new = txt.replace(anchor, insert, 1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(new)
    print(f"patch[{name}]: applied")


def main() -> None:
    if len(sys.argv) != 2:
        print("usage: apply-hermes-vulkan-patches.py <path/to/ggml-vulkan.cpp>", file=sys.stderr)
        sys.exit(1)
    path = sys.argv[1]
    try:
        with open(path, "r", encoding="utf-8") as f:
            f.read()
    except FileNotFoundError:
        print(f"error: {path} not found", file=sys.stderr)
        sys.exit(1)
    apply(path, MARK1, ANCHOR1, INSERT1, "vulkan-device-dispatcher")
    apply(path, MARK2, ANCHOR2, INSERT2, "vulkan-device-memory")


if __name__ == "__main__":
    main()

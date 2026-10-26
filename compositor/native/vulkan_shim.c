/*
 * Minimal Vulkan loader shim for TAWC's app-private Turnip ICD.
 *
 * Zink only needs vkGetInstanceProcAddr / vkGetDeviceProcAddr. Android's
 * system libvulkan.so is a HAL dispatcher and cannot load an app-private
 * ICD JSON, so this tiny library forwards those two entry points straight
 * to the Turnip ICD's vk_icdGetInstanceProcAddr.
 *
 * TAWC_TURNIP_ICD must name the absolute path of libvulkan_freedreno.so.
 */

#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <vulkan/vulkan_core.h>

static void *g_icd_lib;
static PFN_vkGetInstanceProcAddr g_icd_gipa;
static VkInstance g_instance;
static pthread_once_t g_once = PTHREAD_ONCE_INIT;

static void
load_icd(void)
{
   const char *path = getenv("TAWC_TURNIP_ICD");
   if (!path || !path[0])
      path = "libvulkan_freedreno.so";

   g_icd_lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
   if (!g_icd_lib) {
      fprintf(stderr, "tawc-vk: dlopen(%s) failed: %s\n", path, dlerror());
      return;
   }

   *(void **)(&g_icd_gipa) = dlsym(g_icd_lib, "vk_icdGetInstanceProcAddr");
   if (!g_icd_gipa) {
      fprintf(stderr, "tawc-vk: %s has no vk_icdGetInstanceProcAddr\n", path);
      return;
   }

}

static void
ensure_icd(void)
{
   pthread_once(&g_once, load_icd);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
vkGetInstanceProcAddr(VkInstance instance, const char *pName)
{
   ensure_icd();
   if (!g_icd_gipa)
      return NULL;
   if (instance)
      g_instance = instance;
   if (pName && strcmp(pName, "vkGetDeviceProcAddr") == 0)
      return (PFN_vkVoidFunction)vkGetDeviceProcAddr;
   return g_icd_gipa(instance, pName);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
vkGetDeviceProcAddr(VkDevice device, const char *pName)
{
   (void)device;
   ensure_icd();
   if (!g_icd_gipa || !g_instance)
      return NULL;
   /* The ICD has no loader-style vkGetDeviceProcAddr. Its instance proc
    * lookup returns device trampolines for device-level names. */
   return g_icd_gipa(g_instance, pName);
}

/* Some callers resolve these through dlopen+dlsym rather than through
 * vkGetInstanceProcAddr(NULL, ...). */
VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateInstanceVersion(uint32_t *pApiVersion)
{
   ensure_icd();
   PFN_vkEnumerateInstanceVersion fn = (PFN_vkEnumerateInstanceVersion)
      (g_icd_gipa ? g_icd_gipa(NULL, "vkEnumerateInstanceVersion") : NULL);
   if (fn)
      return fn(pApiVersion);
   if (pApiVersion)
      *pApiVersion = VK_API_VERSION_1_0;
   return VK_SUCCESS;
}

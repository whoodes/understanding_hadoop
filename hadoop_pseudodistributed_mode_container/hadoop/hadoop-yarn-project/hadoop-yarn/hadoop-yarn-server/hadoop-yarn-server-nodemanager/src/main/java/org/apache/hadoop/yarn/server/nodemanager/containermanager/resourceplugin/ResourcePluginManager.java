/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.nodemanager.containermanager.resourceplugin;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.yarn.api.records.ResourceInformation;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.server.nodemanager.Context;
import org.apache.hadoop.yarn.server.nodemanager.api.deviceplugin.DevicePlugin;
import org.apache.hadoop.yarn.server.nodemanager.api.deviceplugin.DeviceRegisterRequest;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.resourceplugin.deviceframework.DeviceMappingManager;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.resourceplugin.deviceframework.DevicePluginAdapter;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.resourceplugin.fpga.FpgaResourcePlugin;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.resourceplugin.gpu.GpuResourcePlugin;
import org.apache.hadoop.yarn.util.resource.ResourceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.apache.hadoop.yarn.api.records.ResourceInformation.FPGA_URI;
import static org.apache.hadoop.yarn.api.records.ResourceInformation.GPU_URI;

/**
 * Manages {@link ResourcePlugin} configured on this NodeManager.
 */
public class ResourcePluginManager {
  private static final Logger LOG =
      LoggerFactory.getLogger(ResourcePluginManager.class);
  private static final Set<String> SUPPORTED_RESOURCE_PLUGINS =
      ImmutableSet.of(GPU_URI, FPGA_URI);

  private Map<String, ResourcePlugin> configuredPlugins =
          Collections.emptyMap();

  private DeviceMappingManager deviceMappingManager = null;

  public synchronized void initialize(Context context)
      throws YarnException, ClassNotFoundException {
    Configuration conf = context.getConf();
    Map<String, ResourcePlugin> pluginMap = new HashMap<>();

    String[] plugins = conf.getStrings(YarnConfiguration.NM_RESOURCE_PLUGINS);
    if (plugins == null || plugins.length == 0) {
      LOG.info("No Resource plugins found from configuration!");
    }
    LOG.info("Found Resource plugins from configuration: "
        + Arrays.toString(plugins));

    if (plugins != null) {
      // Initialize each plugins
      for (String resourceName : plugins) {
        resourceName = resourceName.trim();
        if (!SUPPORTED_RESOURCE_PLUGINS.contains(resourceName)) {
          String msg =
              "Trying to initialize resource plugin with name=" + resourceName
                  + ", it is not supported, list of supported plugins:"
                  + StringUtils.join(",", SUPPORTED_RESOURCE_PLUGINS);
          LOG.error(msg);
          throw new YarnException(msg);
        }

        if (pluginMap.containsKey(resourceName)) {
          LOG.warn("Ignoring duplicate Resource plugin definition: " +
              resourceName);
          continue;
        }

        ResourcePlugin plugin = null;
        if (resourceName.equals(GPU_URI)) {
          plugin = new GpuResourcePlugin();
        } else if (resourceName.equals(FPGA_URI)) {
          plugin = new FpgaResourcePlugin();
        }

        if (plugin == null) {
          throw new YarnException(
              "This shouldn't happen, plugin=" + resourceName
                  + " should be loaded and initialized");
        }
        plugin.initialize(context);
        LOG.info("Initialized plugin {}", plugin);
        pluginMap.put(resourceName, plugin);
      }
    }
    // Try to load pluggable device plugins
    boolean puggableDeviceFrameworkEnabled = conf.getBoolean(
        YarnConfiguration.NM_PLUGGABLE_DEVICE_FRAMEWORK_ENABLED,
        YarnConfiguration.DEFAULT_NM_PLUGGABLE_DEVICE_FRAMEWORK_ENABLED);

    if (puggableDeviceFrameworkEnabled) {
      initializePluggableDevicePlugins(context, conf, pluginMap);
    } else {
      LOG.info("The pluggable device framework is not enabled."
              + " If you want, please set true to {}",
          YarnConfiguration.NM_PLUGGABLE_DEVICE_FRAMEWORK_ENABLED);
    }
    configuredPlugins = Collections.unmodifiableMap(pluginMap);
  }

  public void initializePluggableDevicePlugins(Context context,
      Configuration configuration,
      Map<String, ResourcePlugin> pluginMap)
      throws YarnRuntimeException, ClassNotFoundException {
    LOG.info("The pluggable device framework enabled," +
        "trying to load the vendor plugins");
    deviceMappingManager = new DeviceMappingManager(context);
    String[] pluginClassNames = configuration.getStrings(
        YarnConfiguration.NM_PLUGGABLE_DEVICE_FRAMEWORK_DEVICE_CLASSES);
    if (null == pluginClassNames) {
      throw new YarnRuntimeException("Null value found in configuration: "
          + YarnConfiguration.NM_PLUGGABLE_DEVICE_FRAMEWORK_DEVICE_CLASSES);
    }

    for (String pluginClassName : pluginClassNames) {
      Class<?> pluginClazz = Class.forName(pluginClassName);
      if (!DevicePlugin.class.isAssignableFrom(pluginClazz)) {
        throw new YarnRuntimeException("Class: " + pluginClassName
            + " not instance of " + DevicePlugin.class.getCanonicalName());
      }
      // sanity-check before initialization
      checkInterfaceCompatibility(DevicePlugin.class, pluginClazz);

      DevicePlugin dpInstance =
          (DevicePlugin) ReflectionUtils.newInstance(
              pluginClazz, configuration);

      // Try to register plugin
      // TODO: handle the plugin method timeout issue
      DeviceRegisterRequest request = null;
      try {
        request = dpInstance.getRegisterRequestInfo();
      } catch (Exception e) {
        throw new YarnRuntimeException("Exception thrown from plugin's"
            + " getRegisterRequestInfo:"
            + e.getMessage());
      }
      String resourceName = request.getResourceName();
      // check if someone has already registered this resource type name
      if (pluginMap.containsKey(resourceName)) {
        throw new YarnRuntimeException(resourceName
            + " already registered! Please change resource type name"
            + " or configure correct resource type name"
            + " in resource-types.xml for "
            + pluginClassName);
      }
      // check resource name is valid and configured in resource-types.xml
      if (!isConfiguredResourceName(resourceName)) {
        throw new YarnRuntimeException(resourceName
            + " is not configured inside "
            + YarnConfiguration.RESOURCE_TYPES_CONFIGURATION_FILE
            + " , please configure it first");
      }
      LOG.info("New resource type: {} registered successfully by {}",
          resourceName,
          pluginClassName);
      DevicePluginAdapter pluginAdapter = new DevicePluginAdapter(
          resourceName, dpInstance, deviceMappingManager);
      LOG.info("Adapter of {} created. Initializing..", pluginClassName);
      try {
        pluginAdapter.initialize(context);
      } catch (YarnException e) {
        throw new YarnRuntimeException("Adapter of "
            + pluginClassName + " init failed!");
      }
      LOG.info("Adapter of {} init success!", pluginClassName);
      // Store plugin as adapter instance
      pluginMap.put(request.getResourceName(), pluginAdapter);
    } // end for
  }

  @VisibleForTesting
  // Check if the implemented interfaces' signature is compatible
  public void checkInterfaceCompatibility(Class<?> expectedClass,
      Class<?> actualClass) throws YarnRuntimeException{
    LOG.debug("Checking implemented interface's compatibility: {}",
        expectedClass.getSimpleName());
    Method[] expectedDevicePluginMethods = expectedClass.getMethods();

    // Check method compatibility
    boolean found;
    for (Method method: expectedDevicePluginMethods) {
      found = false;
      LOG.debug("Try to find method: {}",
          method.getName());
      for (Method m : actualClass.getDeclaredMethods()) {
        if (m.getName().equals(method.getName())) {
          LOG.debug("Method {} found in class {}",
              actualClass.getSimpleName(),
              m.getName());
          found = true;
          break;
        }
      }
      if (!found) {
        LOG.error("Method {} is not found in plugin",
            method.getName());
        throw new YarnRuntimeException(
            "Method " + method.getName()
                + " is expected but not implemented in "
                + actualClass.getCanonicalName());
      }
    }// end for
    LOG.info("{} compatibility is ok.",
        expectedClass.getSimpleName());
  }

  @VisibleForTesting
  public boolean isConfiguredResourceName(String resourceName) {
    // check configured
    Map<String, ResourceInformation> configuredResourceTypes =
        ResourceUtils.getResourceTypes();
    if (!configuredResourceTypes.containsKey(resourceName)) {
      return false;
    }
    return true;
  }

  public DeviceMappingManager getDeviceMappingManager() {
    return deviceMappingManager;
  }

  public synchronized void cleanup() throws YarnException {
    for (ResourcePlugin plugin : configuredPlugins.values()) {
      plugin.cleanup();
    }
  }

  /**
   * Get resource name (such as gpu/fpga) to plugin references.
   * @return read-only map of resource name to plugins.
   */
  public synchronized Map<String, ResourcePlugin> getNameToPlugins() {
    return configuredPlugins;
  }
}

/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.tools.eclipse.appengine.deploy;

import com.google.cloud.tools.appengine.AppEngineException;
import com.google.cloud.tools.appengine.configuration.AppEngineWebXmlProjectStageConfiguration;
import com.google.cloud.tools.appengine.configuration.AppYamlProjectStageConfiguration;
import com.google.cloud.tools.appengine.operations.AppEngineWebXmlProjectStaging;
import com.google.cloud.tools.appengine.operations.AppYamlProjectStaging;
import com.google.cloud.tools.appengine.operations.CloudSdk;
import com.google.cloud.tools.eclipse.appengine.deploy.util.CloudSdkProcessWrapper;
import java.nio.file.Path;
import java.util.logging.Level;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;

/**
 * Calls the staging operation on an App Engine project.
 */
public class CloudSdkStagingHelper {

  public static final String STANDARD_STAGING_GENERATED_FILES_DIRECTORY =
      "WEB-INF/appengine-generated";

  /**
   * @param explodedWarDirectory the input of the staging operation
   * @param stagingDirectory where the result of the staging operation will be written
   * @param appEngineStandardStaging executes the staging operation
   * @throws AppEngineException when staging fails
   */
  public static void stageStandard(IPath explodedWarDirectory, IPath stagingDirectory,
      AppEngineWebXmlProjectStaging appEngineStandardStaging, IProgressMonitor monitor)
          throws AppEngineException {
    if (monitor.isCanceled()) {
      throw new OperationCanceledException("canceled early");
    }

    SubMonitor progress = SubMonitor.convert(monitor, 1);
    progress.setTaskName(Messages.getString("task.name.stage.project")); //$NON-NLS-1$

    AppEngineWebXmlProjectStageConfiguration stagingConfig =
        AppEngineWebXmlProjectStageConfiguration.builder(
            explodedWarDirectory.toFile().toPath(), stagingDirectory.toFile().toPath())
            .enableJarSplitting(true)
            .disableUpdateCheck(true)
            .build();


    CloudSdk cloudSdk = new CloudSdk.Builder().build();

    Path sdkPath = cloudSdk.getPath();
    java.util.logging.Logger.getLogger(CloudSdkProcessWrapper.class.getName()).log(Level.WARNING, "sdkPath: " + sdkPath.toString());
    Path appEnginePath = cloudSdk.getAppEngineSdkForJavaPath();
    java.util.logging.Logger.getLogger(CloudSdkProcessWrapper.class.getName()).log(Level.WARNING, "appEnginePath: " + appEnginePath.toString());
    
    appEngineStandardStaging.stageStandard(stagingConfig);

    progress.worked(1);
  }

  /**
   * @param appEngineDirectory directory containing {@code app.yaml}
   * @param deployArtifact project to be deploy (such as WAR or JAR)
   * @param stagingDirectory where the result of the staging operation will be written
   * @throws AppEngineException when staging fails
   * @throws OperationCanceledException when user cancels the operation
   */
  public static void stageFlexible(IPath appEngineDirectory, IPath deployArtifact,
      IPath stagingDirectory, IProgressMonitor monitor) throws AppEngineException {
    if (monitor.isCanceled()) {
      throw new OperationCanceledException("canceled early"); //$NON-NLS-1$
    }

    SubMonitor progress = SubMonitor.convert(monitor, 1);
    progress.setTaskName(Messages.getString("task.name.stage.project")); //$NON-NLS-1$

    AppYamlProjectStageConfiguration stagingConfig =
        AppYamlProjectStageConfiguration.builder(appEngineDirectory.toFile().toPath(),
            deployArtifact.toFile().toPath(), stagingDirectory.toFile().toPath()).build();

    AppYamlProjectStaging staging = new AppYamlProjectStaging();
    staging.stageArchive(stagingConfig);

    progress.worked(1);
  }
}

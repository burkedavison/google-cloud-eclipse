/*******************************************************************************
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *******************************************************************************/

package com.google.cloud.tools.eclipse.appengine.login.ui;

import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.repackaged.com.google.common.annotations.VisibleForTesting;
import com.google.cloud.tools.eclipse.appengine.login.GoogleLoginService;
import com.google.cloud.tools.eclipse.appengine.login.Messages;
import com.google.cloud.tools.ide.login.UiFacade;
import com.google.cloud.tools.ide.login.VerificationCodeHolder;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.window.IShellProvider;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.services.IServiceLocator;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LoginServiceUi implements UiFacade {

  private static final Logger logger = Logger.getLogger(LoginServiceUi.class.getName());

  private static final String ERROR_MARKER_USER_CANCELED_LOGIN = "canceled-by-user"; //$NON-NLS-1$

  private IServiceLocator serviceLocator;
  private IShellProvider shellProvider;
  private Display display;

  public LoginServiceUi(IServiceLocator serviceLocator, IShellProvider shellProvider,
      Display display) {
    this.serviceLocator = serviceLocator;
    this.shellProvider = shellProvider;
    this.display = display;
  }

  public void showErrorDialogHelper(String title, String message) {
    MessageDialog.openError(shellProvider.getShell(), title, message);
  }

  @Override
  public boolean askYesOrNo(String title, String message) {
    throw new RuntimeException("Not allowed to ensure non-UI threads don't prompt."); //$NON-NLS-1$
  }

  @Override
  public void showErrorDialog(String title, String message) {
    // Ignore "title" and "message", as they are non-localized hard-coded strings in the library.
    showErrorDialogHelper(Messages.LOGIN_ERROR_DIALOG_TITLE, Messages.LOGIN_ERROR_DIALOG_MESSAGE);
  }

  @Override
  public void notifyStatusIndicator() {
    // Update and refresh the menu, toolbar button, and tooltip.
    display.asyncExec(new Runnable() {
      @Override
      public void run() {
        serviceLocator.getService(ICommandService.class).refreshElements(
            "com.google.cloud.tools.eclipse.appengine.login.commands.loginCommand", //$NON-NLS-1$
            null);
      }
    });
  }

  @Override
  public VerificationCodeHolder obtainVerificationCodeFromExternalUserInteraction(String title) {
    LocalServerReceiver codeReceiver = new LocalServerReceiver();

    try {
      String redirectUrl = codeReceiver.getRedirectUri();
      if (!Program.launch(GoogleLoginService.getGoogleLoginUrl(redirectUrl))) {
        showErrorDialogHelper(
            Messages.LOGIN_ERROR_DIALOG_TITLE, Messages.LOGIN_ERROR_CANNOT_OPEN_BROWSER);
        return null;
      }

      String authorizationCode = showProgressDialogAndWaitForCode(codeReceiver, redirectUrl);
      if (authorizationCode != null) {
        return new VerificationCodeHolder(authorizationCode, redirectUrl);
      }
      return null;

    } catch (IOException ioe) {
      // Don't show an error dialog if a user pressed the cancel button.
      if (!ioe.getMessage().contains(ERROR_MARKER_USER_CANCELED_LOGIN)) {
        showErrorDialogHelper(Messages.LOGIN_ERROR_DIALOG_TITLE,
            Messages.LOGIN_ERROR_LOCAL_SERVER_RUN + ioe.getLocalizedMessage());
      }
      return null;
    }
  }

  private String showProgressDialogAndWaitForCode(
      final LocalServerReceiver codeReceiver, final String redirectUrl) throws IOException {
    try {
      final String[] codeHolder = new String[1];
      final IOException[] exceptionHolder = new IOException[1];
      final Semaphore wait = new Semaphore(0 /* initially zero permit */);

      new ProgressMonitorDialog(shellProvider.getShell()) {
        @Override
        protected void configureShell(Shell shell) {
          super.configureShell(shell);
          shell.setText(Messages.LOGIN_PROGRESS_DIALOG_TITLE);
        }
        @Override
        protected void cancelPressed() {
          stopCodeWaitingJob(redirectUrl);
          wait.release();  // Allow termination of the attached task.
        }
      }.run(true /* fork */, true /* cancelable */, new IRunnableWithProgress() {
        @Override
        public void run(IProgressMonitor monitor)
            throws InvocationTargetException, InterruptedException {
          monitor.beginTask(Messages.LOGIN_PROGRESS_DIALOG_MESSAGE, IProgressMonitor.UNKNOWN);
          // Fork another sub-job to circumvent the limitation of LocalServerReceiver.
          // (See the comments of scheduleCodeWaitingJob().)
          scheduleCodeWaitingJob(
              new LocalServerReceiverWrapper(codeReceiver), wait, codeHolder, exceptionHolder);
          wait.acquire();  // Block until signaled.
        }
      });

      if (exceptionHolder[0] != null) {
        throw exceptionHolder[0];
      }
      return codeHolder[0];

    } catch (InvocationTargetException | InterruptedException ex) {
      // Never thrown from the attached task.
      return null;
    }
  }

  /**
   * Schedule and run a job that calls {@link LocalServerReceiver#waitForCode}. The reason for
   * creating another job inside the job of {@link showProgressDialogAndWaitForCode} is that we
   * cannot have a 100%-guarantee that {@link LocalServerReceiver#waitForCode} will eventually
   * return. (If {@link stopCodeWaitingJob} fails to stop {@link LocalServerReceiver#waitForCode}
   * gracefully, users will be stuck and the IDE has to be killed forcibly.)
   *
   * However, under normal circumstances, {@link stopCodeWaitingJob} will succeed to terminate
   * this sub-job.
   *
   * @return scheduled job
   */
  @VisibleForTesting
  Job scheduleCodeWaitingJob(
      final LocalServerReceiverWrapper codeReceiver, final Semaphore wait,
      final String[] codeHolder, final IOException[] exceptionHolder) {
    Job codeWaitingJob = new Job("Waiting for Authorization Code") { //$NON-NLS-1$
      @Override
      protected IStatus run(IProgressMonitor monitor) {
        try {
          codeHolder[0] = codeReceiver.waitForCode();
        } catch (IOException ioe) {
          exceptionHolder[0] = ioe;
        }
        finally {
          wait.release();  // Terminate the task attached to the ProgressMonitorDialog.
          try {
            codeReceiver.stop();
          } catch (IOException ioe) {
            logger.log(Level.WARNING,
                "Failed to stop the local web server for login.", ioe); //$NON-NLS-1$
          }
        }
        return Status.OK_STATUS;
      }
    };
    codeWaitingJob.setSystem(true);  // Hide the job from UI.
    codeWaitingJob.schedule();
    return codeWaitingJob;
  }

  /**
   * Stops the background task of {@link showProgressDialogAndWaitForCode} by sending a login
   * error (as an HTTP request) to the local server. {@link LocalServerReceiver#waitForCode} will
   * subsequently throw an {@link IOException}.
   */
  @VisibleForTesting
  void stopCodeWaitingJob(final String redirectUrl) {
    // Wrap in a Job for the case where making HTTP connections takes a long time.
    new Job("Terminating Authorization Code Receiver") { //$NON-NLS-1$
      @Override
      protected IStatus run(IProgressMonitor monitor) {
        HttpURLConnection connection = null;

        try {
          URL url = new URL(redirectUrl
              + "?error=" + ERROR_MARKER_USER_CANCELED_LOGIN); //$NON-NLS-1$
          connection = (HttpURLConnection) url.openConnection();
          int responseCode = connection.getResponseCode();
          if (responseCode != HttpURLConnection.HTTP_OK) {
            logger.log(Level.WARNING,
                "Error terminating code waiting job. Response: " + responseCode); //$NON-NLS-1$
          }
        } catch (IOException ioe) {
          logger.log(Level.WARNING, "Error terminating code waiting job", ioe); //$NON-NLS-1$
        } finally {
          if (connection != null) {
            connection.disconnect();
          }
        }
        return Status.OK_STATUS;
      }
    }.schedule();
  }

  @Override
  public String obtainVerificationCodeFromUserInteraction(
      String title, GoogleAuthorizationCodeRequestUrl authCodeRequestUrl) {
    throw new RuntimeException("Not to be called."); //$NON-NLS-1$
  }

  /**
   * A wrapper class that delegates methods to {@link LocalServerReceiver}. Defined only to allow
   * unit testing, since Mockito cannot mock {@link LocalServerReceiver}, which is {@code final}.
   */
  public static class LocalServerReceiverWrapper {

    private LocalServerReceiver receiver;

    public LocalServerReceiverWrapper(LocalServerReceiver receiver) {
      this.receiver = receiver;
    }

    public String waitForCode() throws IOException {
      return receiver.waitForCode();
    }

    public void stop() throws IOException {
      receiver.stop();
    }
  };
}
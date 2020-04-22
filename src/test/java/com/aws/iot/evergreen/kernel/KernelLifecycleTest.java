/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.EZPlugins;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.DeploymentService;
import com.aws.iot.evergreen.ipc.IPCService;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.Log4jLogEventBuilder;
import com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector;
import com.aws.iot.evergreen.testcommons.testutilities.TestUtils;
import com.aws.iot.evergreen.util.Pair;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(ExceptionLogProtector.class)
class KernelLifecycleTest {
    Kernel mockKernel;
    KernelCommandLine mockKernelCommandLine;
    KernelLifecycle kernelLifecycle;
    Context mockContext;

    @TempDir
    protected Path tempRootDir;

    @BeforeEach
    void beforeEach() throws IOException {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());

        mockKernel = mock(Kernel.class);
        mockContext = mock(Context.class);
        Configuration mockConfig = mock(Configuration.class);
        when(mockConfig.getRoot()).thenReturn(mock(Topics.class));
        when(mockKernel.getConfig()).thenReturn(mockConfig);
        when(mockKernel.getContext()).thenReturn(mockContext);
        when(mockKernel.getRootPath()).thenReturn(tempRootDir);
        when(mockKernel.getConfigPath()).thenReturn(tempRootDir.resolve("config"));
        Files.createDirectories(tempRootDir.resolve("config"));
        when(mockContext.get(eq(EZPlugins.class))).thenReturn(mock(EZPlugins.class));
        when(mockContext.get(eq(ExecutorService.class))).thenReturn(mock(ExecutorService.class));
        when(mockContext.get(eq(ScheduledExecutorService.class))).thenReturn(mock(ScheduledExecutorService.class));

        mockKernelCommandLine = spy(new KernelCommandLine(mockKernel));
        kernelLifecycle = new KernelLifecycle(mockKernel, mockKernelCommandLine);
        mockKernel.setKernelLifecycle(kernelLifecycle);
        mockKernel.setKernelCommandLine(mockKernelCommandLine);
    }

    @Test
    void GIVEN_kernel_WHEN_launch_and_main_not_found_THEN_throws_RuntimeException(ExtensionContext context) throws Exception {
        doThrow(ServiceLoadException.class).when(mockKernel).locate(eq("main"));

        ignoreExceptionUltimateCauseOfType(context, ServiceLoadException.class);
        RuntimeException ex = assertThrows(RuntimeException.class, kernelLifecycle::launch);
        assertEquals(RuntimeException.class, ex.getClass());
        assertEquals(ServiceLoadException.class, ex.getCause().getClass());
    }

    @Test
    void GIVEN_kernel_WHEN_launch_with_autostart_services_THEN_autostarts_added_as_dependencies_of_main()
            throws Exception {
        EvergreenService mockMain = mock(EvergreenService.class);
        EvergreenService mockOthers = mock(EvergreenService.class);
        doReturn(mockMain).when(mockKernel).locate(eq("main"));
        doReturn(mockOthers).when(mockKernel).locate(not(eq("main")));

        // Mock out EZPlugins so I can return a deterministic set of services to be added as auto-start
        EZPlugins pluginMock = mock(EZPlugins.class);
        when(mockContext.get(EZPlugins.class)).thenReturn(pluginMock);
        doAnswer((i) -> {
            ClassAnnotationMatchProcessor func = i.getArgument(1);

            func.processMatch(IPCService.class);
            func.processMatch(DeploymentService.class);

            return null;
        }).when(pluginMock).annotated(eq(ImplementsService.class), any());

        kernelLifecycle.launch();
        // Expect 2 times because I returned 2 plugins from above: IPC and Deployment
        verify(mockMain, times(2)).addOrUpdateDependency(eq(mockOthers), eq(State.RUNNING), eq(true));
    }

    @Test
    void GIVEN_kernel_WHEN_launch_without_config_THEN_config_read_from_disk() throws Exception {
        EvergreenService mockMain = mock(EvergreenService.class);
        doReturn(mockMain).when(mockKernel).locate(eq("main"));

        Configuration mockConfig = spy(mockKernel.getConfig());
        mockKernel.setConfig(mockConfig);
        doReturn(mockConfig).when(mockConfig).read(any(Path.class)); // Do nothing when "read" on Config

        // Create configYaml so that the kernel will try to read it in
        File configYaml = mockKernel.getConfigPath().resolve("config.yaml").toFile();
        configYaml.createNewFile();

        kernelLifecycle.launch();
        verify(mockKernel.getConfig()).read(any(Path.class));
    }

    @Test
    void GIVEN_kernel_WHEN_launch_without_config_THEN_tlog_read_from_disk() throws Exception {
        EvergreenService mockMain = mock(EvergreenService.class);
        doReturn(mockMain).when(mockKernel).locate(eq("main"));

        Configuration mockConfig = spy(mockKernel.getConfig());
        mockKernel.setConfig(mockConfig);
        doReturn(mockConfig).when(mockConfig).read(any(Path.class)); // Do nothing when "read" on Config

        // Create configTlog so that the kernel will try to read it in
        File configTlog = mockKernel.getConfigPath().resolve("config.tlog").toFile();
        configTlog.createNewFile();

        kernelLifecycle.launch();
        verify(mockKernel.getConfig()).read(any(Path.class));
    }

    @Test
    void GIVEN_kernel_WHEN_launch_with_config_THEN_effective_config_written() throws Exception {
        EvergreenService mockMain = mock(EvergreenService.class);
        doReturn(mockMain).when(mockKernel).locate(eq("main"));

        mockKernelCommandLine.haveRead = true;

        kernelLifecycle.launch();
        Path configPath = mockKernel.getConfigPath().resolve("config.yaml");
        verify(mockKernel).writeEffectiveConfig(eq(configPath));
    }

    @Test
    void GIVEN_kernel_WHEN_startupAllServices_THEN_services_started_in_order() {
        EvergreenService service1 = mock(EvergreenService.class);
        EvergreenService service2 = mock(EvergreenService.class);
        EvergreenService service3 = mock(EvergreenService.class);
        EvergreenService service4 = mock(EvergreenService.class);
        doNothing().when(service1).requestStart();
        doNothing().when(service2).requestStart();
        doNothing().when(service3).requestStart();
        doNothing().when(service4).requestStart();

        doReturn(Arrays.asList(service1, service2, service3, service4)).when(mockKernel).orderedDependencies();

        kernelLifecycle.startupAllServices();

        InOrder inOrder = inOrder(service1, service2, service3, service4);
        inOrder.verify(service1).requestStart();
        inOrder.verify(service2).requestStart();
        inOrder.verify(service3).requestStart();
        inOrder.verify(service4).requestStart();
    }

    @Test
    void GIVEN_kernel_WHEN_shutdown_twice_THEN_only_1_shutdown_happens() {
        doReturn(Collections.emptyList()).when(mockKernel).orderedDependencies();

        kernelLifecycle.shutdown();
        kernelLifecycle.shutdown();

        verify(mockKernel).orderedDependencies();
    }

    @Test
    void GIVEN_kernel_WHEN_shutdown_THEN_shutsdown_services_in_order(ExtensionContext context) throws Exception {
        EvergreenService badService1 = mock(EvergreenService.class);
        EvergreenService service2 = mock(EvergreenService.class);
        EvergreenService service3 = mock(EvergreenService.class);
        EvergreenService service4 = mock(EvergreenService.class);
        EvergreenService badService5 = mock(EvergreenService.class);

        CompletableFuture<Void> fut = new CompletableFuture<>();
        fut.complete(null);
        CompletableFuture<Void> failedFut = new CompletableFuture<>();
        failedFut.completeExceptionally(new Exception("Service1"));

        doReturn(failedFut).when(badService1).close();
        doReturn(fut).when(service2).close();
        doReturn(fut).when(service3).close();
        doReturn(fut).when(service4).close();
        doThrow(new RuntimeException("Service5")).when(badService5).close();

        doReturn(Arrays.asList(badService1, service2, service3, service4, badService5)).when(mockKernel).orderedDependencies();

        // Check that logging of exceptions works as expected
        // Expect 5 then 1 because our OD is 1->5, so reversed is 5->1.
        CountDownLatch seenErrors = new CountDownLatch(2);
        Pair<CompletableFuture<Void>, Consumer<EvergreenStructuredLogMessage>> listener =
                TestUtils.asyncAssertOnConsumer((m) -> {
            if(m.getEventType().equals("service-shutdown-error")) {
                if (seenErrors.getCount() == 2) {
                    assertEquals("Service5", m.getCause().getMessage());
                } else if (seenErrors.getCount() == 1) {
                    assertEquals("Service1", m.getCause().getMessage());
                }
                seenErrors.countDown();
            }
        });

        ignoreExceptionUltimateCauseWithMessage(context, "Service5");
        ignoreExceptionUltimateCauseWithMessage(context, "Service1");

        Log4jLogEventBuilder.addGlobalListener(listener.getRight());
        kernelLifecycle.shutdown(5);
        Log4jLogEventBuilder.removeGlobalListener(listener.getRight());
        assertTrue(seenErrors.await(1, TimeUnit.SECONDS));
        listener.getLeft().get(1, TimeUnit.SECONDS);

        InOrder inOrder = inOrder(badService5, service4, service3, service2, badService1); // Reverse ordered
        inOrder.verify(badService5).close();
        inOrder.verify(service4).close();
        inOrder.verify(service3).close();
        inOrder.verify(service2).close();
        inOrder.verify(badService1).close();
    }
}
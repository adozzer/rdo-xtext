package ru.bmstu.rk9.rdo.ui.runtime;

import java.util.Timer;
import java.util.TimerTask;

import java.util.LinkedList;

import java.lang.reflect.Method;

import java.io.IOException;

import java.net.URL;
import java.net.URLClassLoader;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;

import org.eclipse.swt.widgets.Display;

import org.eclipse.ui.PlatformUI;

import org.eclipse.ui.handlers.HandlerUtil;

import org.eclipse.ui.services.ISourceProviderService;

import org.eclipse.xtext.builder.EclipseOutputConfigurationProvider;
import org.eclipse.xtext.builder.EclipseResourceFileSystemAccess2;

import org.eclipse.xtext.ui.resource.IResourceSetProvider;

import ru.bmstu.rk9.rdo.IMultipleResourceGenerator;

import com.google.inject.Inject;
import com.google.inject.Provider;

import ru.bmstu.rk9.rdo.lib.Notifier;
import ru.bmstu.rk9.rdo.lib.Result;
import ru.bmstu.rk9.rdo.lib.Simulator;

import ru.bmstu.rk9.rdo.ui.contributions.RDOConsoleView;
import ru.bmstu.rk9.rdo.ui.contributions.RDOTraceView;
import ru.bmstu.rk9.rdo.ui.contributions.RDOStatusView;


public class ExecutionHandler extends AbstractHandler
{
	@Inject
	private IMultipleResourceGenerator generator;
 
	@Inject
	private Provider<EclipseResourceFileSystemAccess2> fileAccessProvider;

	@Inject
	private IResourceSetProvider resourceSetProvider;

	@Inject
	private EclipseOutputConfigurationProvider outputConfigurationProvider;

	private static boolean isRunning = false;

	public static boolean getRunningState()
	{
		return isRunning;
	}

	private static void setRunningState
	(
		Display display,
		final ModelExecutionSourceProvider sourceProvider,
		boolean newstate
	)
	{
		isRunning = newstate;
		display.syncExec
		(
			new Runnable ()
			{
				public void run ()
				{
					sourceProvider.updateRunningState();
				}
			}
		);
	}

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException
	{
		final Display display = PlatformUI.getWorkbench().getDisplay();
		ISourceProviderService sourceProviderService = (ISourceProviderService) HandlerUtil
			.getActiveWorkbenchWindow(event).getService(ISourceProviderService.class);
		final ModelExecutionSourceProvider sourceProvider =
			(ModelExecutionSourceProvider) sourceProviderService.getSourceProvider(
				ModelExecutionSourceProvider.ModelExecutionKey);

		setRunningState(display, sourceProvider, true);

		final Job build = ModelBuilder.build
		(
			event,
			fileAccessProvider.get(),
			resourceSetProvider,
			outputConfigurationProvider,
			generator
		);
		build.schedule();

		final IProject project = ModelBuilder.getProject(HandlerUtil.getActiveEditor(event));

		if(project == null)
		{
			setRunningState(display, sourceProvider, false);
			return null;
		}

		final Job run = new Job(project.getName() + " execution")
		{
			protected IStatus run(IProgressMonitor monitor) 
			{
				String name = this.getName();
				this.setName(name + " (waiting for execution to complete)");

				IJobManager jobMan = Job.getJobManager();
				try
				{
					for (Job j : jobMan.find("rdo_model_run"))
						if (j != this)
							j.join();
				}
				catch (Exception e)
				{
					e.printStackTrace();
				}

				this.setName(name);

				this.setName(name + " (waiting for build to complete)");

				try
				{
					build.join();
				}
				catch (InterruptedException e)
				{
					e.printStackTrace();
				}

				this.setName(name);

				if (build.getResult() != Status.OK_STATUS)
				{
					setRunningState(display, sourceProvider, false);
					RDOConsoleView.addLine("Build failed");
					return new Status(Status.CANCEL, "ru.bmstu.rk9.rdo.ui", "Execution cancelled");
				}

				RDOConsoleView.clearConsoleText();
				SimulationSynchronizer.start();

				URLClassLoader cl = null;
				Timer uiRealTime = new Timer();

				try
				{
					URL model = new URL("file:///" + ResourcesPlugin.getWorkspace().
							getRoot().getLocation().toString() + "/" + project.getName() + "/bin/");

					URL[] urls = new URL[]{ model };

					cl = new URLClassLoader(urls, Simulator.class.getClassLoader());

					Class<?> cls = cl.loadClass("rdo_model.Embedded");

					Method simulation = null;
					Method initialization = null;
					for(Method method : cls.getMethods())
					{
						if(method.getName() == "runSimulation")
							simulation = method;
						if(method.getName() == "initSimulation")
							initialization = method;
					}

					initialization.invoke(null, new Object[]{});

					//TODO find better place to do it
					Simulator.initTracer();
					Notifier notifier = Simulator.getNotifier();

					notifier
						.getSubscription("TimeChange")
							.addSubscriber(SimulationSynchronizer.getInstance().uiTimeUpdater)
							.addSubscriber(SimulationSynchronizer.getInstance().simulationScaleManager);

					notifier
						.getSubscription("StateChange")
							.addSubscriber(SimulationSynchronizer.getInstance().simulationSpeedManager);

					notifier
						.getSubscription("ExecutionAborted")
							.addSubscriber(SimulationSynchronizer.getInstance().simulationStateListener);

					notifier
						.getSubscription("ExecutionComplete")
								.addSubscriber(RDOTraceView.updater);

					RDOConsoleView.addLine("Started model " + project.getName());
					final long startTime = System.currentTimeMillis();

					uiRealTime.scheduleAtFixedRate
					(
						new TimerTask()
						{
							@Override
							public void run()
							{
								display.asyncExec
								(
									new Runnable()
									{	
										@Override
										public void run()
										{
											RDOStatusView.setRealTime(System.currentTimeMillis() - startTime);	
										}
									}
								);
							}
						},
						0,
						100
					);

					LinkedList<Result> results = new LinkedList<Result>();
					int result = -1; 
					if (simulation != null)
					{
						result = (int)simulation.invoke(null, (Object)results);
					}

					display.asyncExec(SimulationSynchronizer.getInstance().uiTimeUpdater.updater);
					setRunningState(display, sourceProvider, false);

					switch (result)
					{
						case 1:
							RDOConsoleView.addLine("Stopped by terminate condition");
							break;
						case -1:
							RDOConsoleView.addLine("Model terminated by user");
							break;
						default:
							RDOConsoleView.addLine("No more events");
					}

					if (results.size() > 0)
						RDOConsoleView.addLine("Results:");
					for (Result r : results)
						RDOConsoleView.addLine(r.get());

					RDOConsoleView.addLine("Time elapsed: " +
						String.valueOf(System.currentTimeMillis() - startTime) + "ms");
				}
				catch (Exception e)
				{
					setRunningState(display, sourceProvider, false);
					RDOConsoleView.addLine("Execution error");
					e.printStackTrace();
					display.asyncExec(SimulationSynchronizer.getInstance().uiTimeUpdater.updater);
					SimulationSynchronizer.finish();

					uiRealTime.cancel();

					if(cl != null)
						try
						{
							cl.close();
						}
						catch (IOException e1)
						{
							e1.printStackTrace();
						}

					return new Status(Status.CANCEL, "ru.bmstu.rk9.rdo.ui", "Execution failed");
				}

				SimulationSynchronizer.finish();

				uiRealTime.cancel();

				try
				{
					cl.close();
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}

				return Status.OK_STATUS;
			}

			@Override
			public boolean belongsTo(Object family)
			{
				return ("rdo_model_run").equals(family);
			}
		};
		run.setPriority(Job.LONG);
		run.schedule();

		return null;
	}
}

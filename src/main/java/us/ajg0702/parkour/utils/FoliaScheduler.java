package us.ajg0702.parkour.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class FoliaScheduler {

	private static final boolean FOLIA = hasMethod(Bukkit.class, "getGlobalRegionScheduler");

	private FoliaScheduler() { }

	public static boolean isFolia() {
		return FOLIA;
	}

	public static Task run(Plugin plugin, Runnable runnable) {
		if(FOLIA) {
			return global(plugin, "run", runnable);
		}
		return new Task(Bukkit.getScheduler().runTask(plugin, runnable));
	}

	public static Task runAtLocation(Plugin plugin, Location location, Runnable runnable) {
		if(location == null || !FOLIA) {
			return run(plugin, runnable);
		}
		return region(plugin, location, "run", runnable);
	}

	public static Task runForEntity(Plugin plugin, Entity entity, Runnable runnable) {
		if(entity == null || !FOLIA) {
			return run(plugin, runnable);
		}
		return entity(plugin, entity, "run", runnable);
	}

	public static Task runDelayed(Plugin plugin, Runnable runnable, long delayTicks) {
		if(FOLIA) {
			return global(plugin, "runDelayed", runnable, ticks(delayTicks));
		}
		return new Task(Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks));
	}

	public static Task runDelayedAtLocation(Plugin plugin, Location location, Runnable runnable, long delayTicks) {
		if(location == null || !FOLIA) {
			return runDelayed(plugin, runnable, delayTicks);
		}
		return region(plugin, location, "runDelayed", runnable, ticks(delayTicks));
	}

	public static Task runDelayedForEntity(Plugin plugin, Entity entity, Runnable runnable, long delayTicks) {
		if(entity == null || !FOLIA) {
			return runDelayed(plugin, runnable, delayTicks);
		}
		return entity(plugin, entity, "runDelayed", runnable, ticks(delayTicks));
	}

	public static Task runTimer(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
		if(FOLIA) {
			return global(plugin, "runAtFixedRate", runnable, ticks(delayTicks), ticks(periodTicks));
		}
		return new Task(Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks));
	}

	public static Task runTimerAtLocation(Plugin plugin, Location location, Runnable runnable, long delayTicks, long periodTicks) {
		if(location == null || !FOLIA) {
			return runTimer(plugin, runnable, delayTicks, periodTicks);
		}
		return region(plugin, location, "runAtFixedRate", runnable, ticks(delayTicks), ticks(periodTicks));
	}

	public static Task runTimerForEntity(Plugin plugin, Entity entity, Runnable runnable, long delayTicks, long periodTicks) {
		if(entity == null || !FOLIA) {
			return runTimer(plugin, runnable, delayTicks, periodTicks);
		}
		return entity(plugin, entity, "runAtFixedRate", runnable, ticks(delayTicks), ticks(periodTicks));
	}

	public static Task runAsync(Plugin plugin, Runnable runnable) {
		if(FOLIA) {
			return async(plugin, "runNow", runnable);
		}
		return new Task(Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable));
	}

	public static Task runAsyncDelayed(Plugin plugin, Runnable runnable, long delayTicks) {
		if(FOLIA) {
			return async(plugin, "runDelayed", runnable, millis(delayTicks));
		}
		return new Task(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, delayTicks));
	}

	public static Task runAsyncTimer(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
		if(FOLIA) {
			return async(plugin, "runAtFixedRate", runnable, millis(delayTicks), millis(periodTicks));
		}
		return new Task(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, delayTicks, periodTicks));
	}

	private static Task global(Plugin plugin, String method, Runnable runnable, long... ticks) {
		try {
			Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
			return invokeTask(scheduler, method, params(plugin, runnable, ticks));
		} catch(ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to use Folia global scheduler", e);
		}
	}

	private static Task region(Plugin plugin, Location location, String method, Runnable runnable, long... ticks) {
		try {
			Object scheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
			Object[] params = params(plugin, runnable, ticks);
			Object[] regionParams = new Object[params.length + 1];
			regionParams[0] = plugin;
			regionParams[1] = location;
			System.arraycopy(params, 1, regionParams, 2, params.length - 1);
			return invokeTask(scheduler, method, regionParams);
		} catch(ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to use Folia region scheduler", e);
		}
	}

	private static Task entity(Plugin plugin, Entity entity, String method, Runnable runnable, long... ticks) {
		try {
			Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
			Object[] params = params(plugin, runnable, ticks);
			Object[] entityParams = new Object[params.length + 1];
			entityParams[0] = plugin;
			entityParams[1] = params[1];
			entityParams[2] = null;
			if(ticks.length > 0) {
				System.arraycopy(params, 2, entityParams, 3, params.length - 2);
			}
			return invokeTask(scheduler, method, entityParams);
		} catch(ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to use Folia entity scheduler", e);
		}
	}

	private static Task async(Plugin plugin, String method, Runnable runnable, long... millis) {
		try {
			Object scheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
			Object[] params;
			if(millis.length == 0) {
				params = new Object[] { plugin, consumer(runnable) };
			} else if(millis.length == 1) {
				params = new Object[] { plugin, consumer(runnable), millis[0], TimeUnit.MILLISECONDS };
			} else {
				params = new Object[] { plugin, consumer(runnable), millis[0], millis[1], TimeUnit.MILLISECONDS };
			}
			return invokeTask(scheduler, method, params);
		} catch(ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to use Folia async scheduler", e);
		}
	}

	private static Object[] params(Plugin plugin, Runnable runnable, long... ticks) {
		if(ticks.length == 0) {
			return new Object[] { plugin, consumer(runnable) };
		}
		if(ticks.length == 1) {
			return new Object[] { plugin, consumer(runnable), ticks[0] };
		}
		return new Object[] { plugin, consumer(runnable), ticks[0], ticks[1] };
	}

	private static Task invokeTask(Object scheduler, String methodName, Object[] params) throws ReflectiveOperationException {
		for(Method method : scheduler.getClass().getMethods()) {
			if(!method.getName().equals(methodName) || method.getParameterTypes().length != params.length) {
				continue;
			}
			try {
				return new Task(method.invoke(scheduler, params));
			} catch(InvocationTargetException e) {
				Throwable cause = e.getCause();
				if(cause instanceof RuntimeException) {
					throw (RuntimeException) cause;
				}
				throw new IllegalStateException(cause);
			}
		}
		throw new NoSuchMethodException(methodName);
	}

	private static Consumer<Object> consumer(Runnable runnable) {
		return ignored -> runnable.run();
	}

	private static boolean hasMethod(Class<?> clazz, String methodName) {
		try {
			clazz.getMethod(methodName);
			return true;
		} catch(NoSuchMethodException e) {
			return false;
		}
	}

	private static long ticks(long ticks) {
		return Math.max(1, ticks);
	}

	private static long millis(long ticks) {
		return ticks(ticks) * 50L;
	}

	public static class Task {
		private final Object task;

		private Task(Object task) {
			this.task = task;
		}

		public void cancel() {
			if(task == null) {
				return;
			}
			try {
				task.getClass().getMethod("cancel").invoke(task);
			} catch(ReflectiveOperationException e) {
				throw new IllegalStateException("Unable to cancel scheduled task", e);
			}
		}
	}
}

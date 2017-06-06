package io.github.khangnt.downloader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import io.github.khangnt.downloader.exception.TaskNotFoundException;
import io.github.khangnt.downloader.model.Chunk;
import io.github.khangnt.downloader.model.Task;
import io.github.khangnt.downloader.worker.ChunkWorker;
import io.github.khangnt.downloader.worker.ChunkWorkerListener;
import io.github.khangnt.downloader.worker.MergeFileWorker;
import io.github.khangnt.downloader.worker.MergeFileWorkerListener;
import io.github.khangnt.downloader.worker.ModeratorExecutor;

/**
 * Created by Khang NT on 6/2/17.
 * Email: khang.neon.1997@gmail.com
 */

public class FileDownloader implements IFileDownloader, ChunkWorkerListener, MergeFileWorkerListener {
    public static final String MODERATOR_THREAD = "ModeratorThread";

    private static final String CHUNK_KEY_PREFIX = "chunk:";
    private static final String MERGE_KEY_PREFIX = "merge:";

    private final Object lock = new Object();

    private FileManager mFileManager;
    private HttpClient mHttpClient;
    private TaskManager mTaskManager;
    private DownloadSpeedMeter mDownloadSpeedMeter;

    private EventDispatcher mEventDispatcher;
    private Map<String, Thread> mWorkers;
    private ModeratorExecutor mModeratorExecutor;

    private boolean mRunning;

    private int mMaxWorker;

    public FileDownloader(FileManager fileManager, HttpClient httpClient, TaskManager taskManager) {
        mFileManager = fileManager;
        mHttpClient = httpClient;
        mTaskManager = taskManager;

        mRunning = false;
        mEventDispatcher = new EventDispatcher();
        mDownloadSpeedMeter = new DownloadSpeedMeter();
        mWorkers = new HashMap<>();
        mModeratorExecutor = new ModeratorExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                return new Thread(runnable, MODERATOR_THREAD);
            }
        });
    }

    @Override
    public Task addTask(Task task) {
        Task result = getTaskManager().insertTask(task);
        if (isRunning()) spawnWorker();
        return result;
    }

    @Override
    public void cancelTask(int taskId) {
        Task task = getTaskManager().findTask(taskId);
        if (task == null) {
            throw new TaskNotFoundException("No task exists with this ID: " + taskId);
        }
        cancelTaskInternal(task, "Cancelled");
    }

    @Override
    public void start() {
        synchronized (lock) {
            mRunning = true;
            spawnWorker();
            mDownloadSpeedMeter.start();
        }
    }

    @Override
    public void pause() {
        synchronized (lock) {
            mRunning = false;
            mDownloadSpeedMeter.pause();
            mModeratorExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    for (Thread thread : mWorkers.values()) {
                        thread.interrupt();
                    }
                    mWorkers.clear();
                }
            });
        }
    }

    @Override
    public void release() {
        synchronized (lock) {
            pause();
            mModeratorExecutor.executeAllPendingRunnable();
            mEventDispatcher.unregisterAllListener();
            mTaskManager.release();
            mFileManager = null;
            mHttpClient = null;
            mTaskManager = null;
        }
    }

    @Override
    public boolean isRunning() {
        return mRunning;
    }

    @Override
    public boolean isReleased() {
        return getFileManager() == null || getTaskManager() == null || getHttpClient() == null;
    }

    @Override
    public int getMaxWorkers() {
        return mMaxWorker;
    }

    @Override
    public void setMaxWorkers(int maxWorkers) {
        synchronized (lock) {
            if (maxWorkers != mMaxWorker) {
                if (maxWorkers < 0)
                    throw new IllegalArgumentException("Number of workers must > 0");
                mMaxWorker = maxWorkers;
            }
            if (mRunning) spawnWorker();
        }
    }

    @Override
    public void registerListener(EventListener listener, Executor executor) {
        mEventDispatcher.registerListener(executor, listener);
    }

    @Override
    public void clearAllListener() {
        mEventDispatcher.unregisterAllListener();
    }

    @Override
    public void unregisterListener(EventListener listener) {
        mEventDispatcher.unregisterListener(listener);
    }

    @Override
    public long getSpeed() {
        return mDownloadSpeedMeter.getSpeed();
    }

    @Override
    public TaskManager getTaskManager() {
        return mTaskManager;
    }

    @Override
    public HttpClient getHttpClient() {
        return mHttpClient;
    }

    @Override
    public FileManager getFileManager() {
        return mFileManager;
    }

    protected void spawnWorker() {
        mModeratorExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (!Thread.currentThread().getName().equals(MODERATOR_THREAD))
                    throw new IllegalStateException("Spawn worker must run on Moderator thread");
                if (!isRunning() || Thread.interrupted()) return;
                List<Task> unfinishedTasks = getTaskManager().getUndoneTasks();
                for (Task task : unfinishedTasks) {
                    if (!isRunning() || Thread.interrupted()) return;
                    if (task.getState() == Task.State.IDLE) try {
                        task = initTask(task);
                    } catch (Exception e) {
                        Log.e(e, "Failed to initialize task-%d", task.getId());
                        // INIT -> FAILED
                        getTaskManager().updateTask(task.newBuilder().setState(Task.State.FAILED)
                                .setMessage("Failed to read content length: " + e.getMessage())
                                .build());
                    }
                    if (mWorkers.size() < getMaxWorkers()) {
                        List<Chunk> chunks = mTaskManager.getChunksOfTask(task);
                        if (areAllChunkFinished(chunks)) {
                            getTaskManager().updateTask(task.newBuilder().setState(Task.State.MERGING)
                                    .build());
                            spawnMergeFileWorkerIfNotExists(task, chunks);
                        } else {
                            spawnChunkWorkerIfNotExists(task, chunks);
                            splitLargeChunkIfPossible(task);
                        }
                    }
                }
            }
        });
    }

    protected Task initTask(Task task) {
        Log.d("Initializing task-%d...", task.getId());
        mTaskManager.removeChunksOfTask(task);
        Task.Builder after = task.newBuilder();
        if (after.getLength() == C.UNSET)
            after.setLength(getHttpClient().fetchContentLength(task));
        if (!after.isResumable()) {
            getTaskManager().insertChunk(new Chunk.Builder(after.getId()).build());
        } else {
            long length = after.getLength();
            int numberOfChunks = 1;
            while (numberOfChunks < after.getMaxChunks()
                    && length / (numberOfChunks + 1) > C.MIN_CHUNK_LENGTH)
                numberOfChunks++;
            final long lengthPerChunk = length / numberOfChunks;
            for (int i = 0; i < numberOfChunks - 1; i++) {
                getTaskManager().insertChunk(new Chunk.Builder(after.getId())
                        .setRange(i * lengthPerChunk, (i + 1) * lengthPerChunk - 1)
                        .build());
            }
            getTaskManager().insertChunk(new Chunk.Builder(after.getId())
                    .setRange((numberOfChunks - 1) * lengthPerChunk, length - 1)
                    .build());
        }
        // INIT -> WAITING
        return mTaskManager.updateTask(after.setState(Task.State.WAITING).build());
    }

    protected void spawnChunkWorkerIfNotExists(Task task, List<Chunk> chunks) {
        for (Chunk chunk : chunks) {
            if (!isRunning() || Thread.interrupted()) return;
            if (chunk.isFinished()) continue;
            if (mWorkers.size() < getMaxWorkers()) {
                ChunkWorker chunkWorker = (ChunkWorker) mWorkers.get(CHUNK_KEY_PREFIX + chunk.getId());
                if (chunkWorker == null) {
                    chunkWorker = new ChunkWorker(chunk, mFileManager.getChunkFile(task, chunk.getId()),
                            getHttpClient(), getTaskManager(), getFileManager(), mDownloadSpeedMeter, this);
                    chunkWorker.start();
                    Log.d("Spawn worker %s for task %d", CHUNK_KEY_PREFIX + chunk.getId(), task.getId());
                    mWorkers.put(CHUNK_KEY_PREFIX + chunk.getId(), chunkWorker);
                }
            } else {
                break;
            }
        }
    }

    protected void spawnMergeFileWorkerIfNotExists(Task task, List<Chunk> chunks) {
        MergeFileWorker mergeFileWorker = (MergeFileWorker) mWorkers.get(MERGE_KEY_PREFIX + task.getId());
        if (mergeFileWorker == null) {
            mergeFileWorker = new MergeFileWorker(task, chunks, getFileManager(), this);
            mergeFileWorker.start();
            Log.d("Spawn worker %s for task %d", MERGE_KEY_PREFIX + task.getId(), task.getId());
            mWorkers.put(MERGE_KEY_PREFIX + task.getId(), mergeFileWorker);

        }
    }

    protected void splitLargeChunkIfPossible(Task task) {
        if (!task.isResumable()) return;
        List<ChunkWorker> runningChunks = new ArrayList<ChunkWorker>();
        for (Thread thread : mWorkers.values()) {
            if (thread instanceof ChunkWorker) {
                ChunkWorker worker = (ChunkWorker) thread;
                if (worker.getChunk().getTaskId() == task.getId())
                    runningChunks.add(worker);
            }
        }
        int maxWorkersCanSpawn = Math.min(getMaxWorkers() - mWorkers.size(),
                task.getMaxParallelConnections() - runningChunks.size());
        if (maxWorkersCanSpawn > 0) {
            // sort running chunk workers by remaining bytes of chunk
            Collections.sort(runningChunks, new Comparator<ChunkWorker>() {
                @Override
                public int compare(ChunkWorker c1, ChunkWorker c2) {
                    return - Long.compare(c1.getRemainingBytes(), c2.getRemainingBytes());
                }
            });
            for (ChunkWorker worker : runningChunks) {
                Chunk newChunk = worker.splitChunk();
                if (newChunk == null) return;
                spawnChunkWorkerIfNotExists(task, Collections.singletonList(newChunk));
                if (--maxWorkersCanSpawn == 0) return;
            }
        }
    }

    protected boolean areAllChunkFinished(List<Chunk> chunks) {
        for (Chunk chunk : chunks) {
            if (!chunk.isFinished()) return false;
        }
        return true;
    }

    private void cancelTaskInternal(final Task task, String message) {
        getTaskManager().updateTask(task.newBuilder().setState(Task.State.FAILED)
                .setMessage(message)
                .build());
        mModeratorExecutor.execute(new Runnable() {
            @Override
            public void run() {
                List<Chunk> chunksOfTask = getTaskManager().getChunksOfTask(task);
                for (Chunk chunk : chunksOfTask) {
                    ChunkWorker worker = (ChunkWorker) mWorkers.remove(CHUNK_KEY_PREFIX + chunk.getId());
                    if (worker != null) {
                        worker.interrupt();
                        try {
                            worker.join();
                        } catch (InterruptedException ignore) {
                        }
                        String chunkFile = getFileManager().getChunkFile(task, chunk.getId());
                        getFileManager().deleteFile(chunkFile);
                    }
                }
            }
        });
    }

    @Override
    public void onChunkFinished(final ChunkWorker worker) {
        Log.d("Chunk-%d finished", worker.getChunk().getId());
        mModeratorExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mWorkers.remove(CHUNK_KEY_PREFIX + worker.getChunk().getId());
            }
        });
        synchronized (lock) {
            if (isRunning()) spawnWorker();
        }
    }

    @Override
    public void onChunkError(ChunkWorker worker, String reason, Throwable throwable) {
        Log.e(throwable, "Chunk-%d failed: %s", worker.getChunk().getId(), reason);
        // download chunk error ==> the task also error
        synchronized (lock) {
            if (isRunning()) {
                spawnWorker();
            }
            if (!isReleased()) {
                Task task = getTaskManager().findTask(worker.getChunk().getTaskId());
                if (task == null)
                    throw new TaskNotFoundException("Something went wrong, task was removed while chunks were downloading");
                cancelTaskInternal(task, reason);
            }
        }
    }

    @Override
    public void onChunkInterrupted(final ChunkWorker worker) {
        mModeratorExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mWorkers.remove(CHUNK_KEY_PREFIX + worker.getChunk().getId());
            }
        });
        Log.d("Chunk-%d is interrupted", worker.getChunk().getId());
    }

    @Override
    public void onMergeFileFinished(final MergeFileWorker worker) {
        final Task task = worker.getTask();
        Log.d("Merge task-%d is finished", task.getId());
        synchronized (lock) {
            if (isRunning()) spawnWorker();
            if (!isReleased()) {
                getTaskManager().updateTask(task.newBuilder().setState(Task.State.FINISHED)
                        .setMessage("Successful").build());
                mModeratorExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        mWorkers.remove(MERGE_KEY_PREFIX + worker.getTask().getId());
                        List<Chunk> chunks = getTaskManager().getChunksOfTask(task);
                        for (Chunk chunk : chunks) {
                            String chunkFile = getFileManager().getChunkFile(task, chunk.getId());
                            getFileManager().deleteFile(chunkFile);
                        }
                    }
                });
            }
        }
    }

    @Override
    public void onMergeFileError(final MergeFileWorker worker, String reason, Throwable error) {
        Task task = worker.getTask();
        Log.e(error, "Merge task-%d failed: %s", task.getId(), reason);
        mModeratorExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mWorkers.remove(MERGE_KEY_PREFIX + worker.getTask().getId());
            }
        });
        synchronized (lock) {
            if (isRunning()) spawnWorker();
            if (!isReleased()) cancelTaskInternal(task, reason);
        }
    }

    @Override
    public void onMergeFileInterrupted(final MergeFileWorker worker) {
        Log.d("Merge file interrupted (task-%d)", worker.getTask().getId());
        mModeratorExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mWorkers.remove(MERGE_KEY_PREFIX + worker.getTask().getId());
            }
        });
    }
}

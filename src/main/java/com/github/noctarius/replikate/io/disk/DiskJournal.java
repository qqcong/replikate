package com.github.noctarius.replikate.io.disk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.noctarius.replikate.JournalBatch;
import com.github.noctarius.replikate.JournalEntry;
import com.github.noctarius.replikate.JournalListener;
import com.github.noctarius.replikate.JournalNamingStrategy;
import com.github.noctarius.replikate.JournalRecord;
import com.github.noctarius.replikate.exceptions.JournalException;
import com.github.noctarius.replikate.exceptions.SynchronousJournalException;
import com.github.noctarius.replikate.spi.AbstractJournal;
import com.github.noctarius.replikate.spi.JournalEntryReader;
import com.github.noctarius.replikate.spi.JournalEntryWriter;
import com.github.noctarius.replikate.spi.JournalRecordIdGenerator;

public class DiskJournal<V>
    extends AbstractJournal<V>
{

    public static final int JOURNAL_FILE_HEADER_SIZE = 25;

    public static final int JOURNAL_RECORD_HEADER_SIZE = 17;

    public static final int JOURNAL_OVERFLOW_OVERHEAD_SIZE = JOURNAL_FILE_HEADER_SIZE + JOURNAL_RECORD_HEADER_SIZE;

    public static final byte JOURNAL_FILE_TYPE_DEFAULT = 1;

    public static final byte JOURNAL_FILE_TYPE_OVERFLOW = 2;

    public static final byte JOURNAL_FILE_TYPE_BATCH = 3;

    private static final Logger LOGGER = LoggerFactory.getLogger( DiskJournal.class );

    private final BlockingQueue<Tuple<JournalEntry<V>, JournalListener<V>>> journalQueue = new LinkedBlockingQueue<>();

    private final DiskJournalWriterTask diskJournalWriterTask = new DiskJournalWriterTask();

    private final Deque<DiskJournalFile<V>> journalFiles = new ConcurrentLinkedDeque<>();

    private final Thread diskJournalWriter;

    private final CountDownLatch shutdownLatch = new CountDownLatch( 1 );

    private final JournalListener<V> listener;

    private final Path journalingPath;

    public DiskJournal( String name, Path journalingPath, JournalListener<V> listener, int maxLogFileSize,
                        JournalRecordIdGenerator recordIdGenerator, JournalEntryReader<V> reader,
                        JournalEntryWriter<V> writer, JournalNamingStrategy namingStrategy )
        throws IOException
    {
        super( name, maxLogFileSize, recordIdGenerator, reader, writer, namingStrategy );
        this.journalingPath = journalingPath;
        this.listener = listener;

        if ( !Files.isDirectory( journalingPath, LinkOption.NOFOLLOW_LINKS ) )
        {
            throw new IllegalArgumentException( "journalingPath is not a directory" );
        }

        LOGGER.info( "{}: DiskJournal starting up in {}...", getName(), journalingPath.toFile().getAbsolutePath() );

        boolean needsReplay = false;
        File path = journalingPath.toFile();
        for ( File child : path.listFiles() )
        {
            if ( child.isDirectory() )
                continue;

            String filename = child.getName();
            if ( namingStrategy.isJournal( filename ) )
            {
                // At least one journal file is still existing so start replay
                needsReplay = true;
                break;
            }
        }

        if ( needsReplay )
        {
            LOGGER.warn( "{}: Found old journals in journaling path, starting replay...", getName() );
            DiskJournalReplayer<V> replayer = new DiskJournalReplayer<>( this, listener );
            replayer.replay();
        }

        // Startup asynchronous journal writer
        diskJournalWriter = new Thread( diskJournalWriterTask, "DiskJournalWriter-" + name );
        diskJournalWriter.start();

        // Replay not required or succeed so start new journal
        journalFiles.push( buildJournalFile() );
    }

    @Override
    public void appendEntry( JournalEntry<V> entry )
        throws JournalException
    {
        appendEntry( entry, listener );
    }

    @Override
    public void appendEntry( JournalEntry<V> entry, JournalListener<V> listener )
        throws JournalException
    {
        try
        {
            DiskJournalEntryFacade<V> journalEntry = DiskJournalIOUtils.prepareJournalEntry( entry, getWriter() );
            journalQueue.offer( new Tuple<JournalEntry<V>, JournalListener<V>>( journalEntry, listener ) );
        }
        catch ( IOException e )
        {
            throw new SynchronousJournalException( "Could not prepare journal entry", e );
        }
    }

    @Override
    public void appendEntrySynchronous( JournalEntry<V> entry )
        throws JournalException
    {
        appendEntrySynchronous( entry, listener );
    }

    @Override
    public void appendEntrySynchronous( JournalEntry<V> entry, JournalListener<V> listener )
        throws JournalException
    {
        try
        {
            synchronized ( journalFiles )
            {
                DiskJournalFile<V> journalFile = journalFiles.peek();
                DiskJournalEntryFacade<V> recordEntry = DiskJournalIOUtils.prepareJournalEntry( entry, getWriter() );

                Tuple<DiskJournalAppendResult, JournalRecord<V>> result = journalFile.appendRecord( recordEntry );
                if ( result.getValue1() == DiskJournalAppendResult.APPEND_SUCCESSFUL )
                {
                    if ( listener != null )
                    {
                        listener.onCommit( result.getValue2() );
                    }
                }
                else if ( result.getValue1() == DiskJournalAppendResult.JOURNAL_OVERFLOW )
                {
                    LOGGER.debug( "Journal full, overflowing to next one..." );

                    // Close current journal file ...
                    journalFile.close();

                    // ... and start new journal ...
                    journalFiles.push( buildJournalFile() );

                    // ... finally retry to write to journal
                    appendEntrySynchronous( entry, listener );
                }
                else if ( result.getValue1() == DiskJournalAppendResult.JOURNAL_FULL_OVERFLOW )
                {
                    LOGGER.debug( "Record dataset too large for normal journal, using overflow journal file" );

                    // Close current journal file ...
                    journalFile.close();

                    // Calculate overflow filelength
                    int length = recordEntry.cachedData.length + DiskJournal.JOURNAL_OVERFLOW_OVERHEAD_SIZE;

                    // ... and start new journal ...
                    journalFile = buildJournalFile( length, DiskJournal.JOURNAL_FILE_TYPE_OVERFLOW );
                    journalFiles.push( journalFile );

                    // ... finally retry to write to journal
                    result = journalFile.appendRecord( recordEntry );
                    if ( result.getValue1() != DiskJournalAppendResult.APPEND_SUCCESSFUL )
                    {
                        throw new SynchronousJournalException( "Overflow file could not be written" );
                    }

                    // Notify listeners about flushed to journal
                    if ( listener != null )
                    {
                        listener.onCommit( result.getValue2() );
                    }
                }
            }
        }
        catch ( IOException e )
        {
            if ( listener != null )
            {
                listener.onFailure( entry, new SynchronousJournalException( "Failed to persist journal entry", e ) );
            }
        }
    }

    @Override
    public JournalBatch<V> startBatchProcess()
    {
        return startBatchProcess( listener );
    }

    @Override
    public JournalBatch<V> startBatchProcess( JournalListener<V> listener )
    {
        return new DiskJournalBatchProcess<>( this, listener );
    }

    @Override
    public long getLastRecordId()
    {
        return getRecordIdGenerator().lastGeneratedRecordId();
    }

    @Override
    public void close()
        throws IOException
    {
        synchronized ( journalFiles )
        {
            try
            {
                diskJournalWriterTask.shutdown();

                // Wait for asynchronous journal writer to finish
                shutdownLatch.await();

                for ( DiskJournalFile<V> journalFile : journalFiles )
                {
                    journalFile.close();
                }
            }
            catch ( InterruptedException e )
            {

            }
        }
    }

    public Path getJournalingPath()
    {
        return journalingPath;
    }

    void commitBatchProcess( JournalBatch<V> journalBatch, List<DiskJournalEntryFacade<V>> entries, int dataSize,
                             JournalListener<V> listener )
        throws JournalException
    {
        synchronized ( journalFiles )
        {
            // Storing current recordId for case of rollback
            long markedRecordId = getRecordIdGenerator().lastGeneratedRecordId();

            try
            {
                // Calculate the file size of the batch journal file ...
                int calculatedLogFileSize =
                    DiskJournal.JOURNAL_FILE_HEADER_SIZE + dataSize
                        + ( entries.size() * DiskJournal.JOURNAL_RECORD_HEADER_SIZE );

                // ... and start new journal
                DiskJournalFile<V> journalFile = buildJournalFile( calculatedLogFileSize, JOURNAL_FILE_TYPE_BATCH );
                journalFiles.push( journalFile );

                // Persist all entries to disk ...
                List<JournalRecord<V>> records = new LinkedList<>();
                for ( DiskJournalEntryFacade<V> entry : entries )
                {
                    Tuple<DiskJournalAppendResult, JournalRecord<V>> result = journalFile.appendRecord( entry );
                    if ( result.getValue1() != DiskJournalAppendResult.APPEND_SUCCESSFUL )
                    {
                        throw new SynchronousJournalException( "Failed to persist journal entry" );
                    }
                }

                // ... and if non of them failed just announce them as committed
                for ( JournalRecord<V> record : records )
                {
                    listener.onCommit( record );
                }
            }
            catch ( Exception e )
            {
                if ( listener != null )
                {
                    listener.onFailure( journalBatch,
                                        new SynchronousJournalException( "Failed to persist journal batch process", e ) );
                }

                // Rollback the journal file
                DiskJournalFile<V> journalFile = journalFiles.pop();
                try
                {
                    journalFile.close();
                    String fileName = journalFile.getFileName();
                    Files.delete( journalingPath.resolve( fileName ) );
                }
                catch ( IOException ioe )
                {
                    throw new JournalException( "Could not rollback journal batch file", ioe );
                }

                // Rollback the recordId
                getRecordIdGenerator().notifyHighestJournalRecordId( markedRecordId );
            }
        }
    }

    void pushJournalFileFromReplay( DiskJournalFile<V> diskJournalFile )
    {
        synchronized ( journalFiles )
        {
            journalFiles.push( diskJournalFile );
            setCurrentLogNumber( diskJournalFile.getLogNumber() );
        }
    }

    private DiskJournalFile<V> buildJournalFile()
        throws IOException
    {
        return buildJournalFile( getMaxLogFileSize(), DiskJournal.JOURNAL_FILE_TYPE_DEFAULT );
    }

    private DiskJournalFile<V> buildJournalFile( int maxLogFileSize, byte type )
        throws IOException
    {
        long logNumber = nextLogNumber();
        String filename = getNamingStrategy().generate( logNumber );
        File journalFile = new File( journalingPath.toFile(), filename );
        return new DiskJournalFile<>( this, journalFile, logNumber, maxLogFileSize, type );
    }

    private class DiskJournalWriterTask
        implements Runnable
    {

        private final AtomicBoolean shutdown = new AtomicBoolean( false );

        private final BlockingQueue<Tuple<JournalEntry<V>, JournalListener<V>>> queue = journalQueue;

        @Override
        public void run()
        {
            try
            {
                while ( true )
                {
                    // If all work is done, break up
                    if ( shutdown.get() && queue.size() == 0 )
                    {
                        break;
                    }

                    Tuple<JournalEntry<V>, JournalListener<V>> tuple = queue.take();
                    if ( tuple != null )
                    {
                        appendEntrySynchronous( tuple.getValue1(), tuple.getValue2() );
                    }

                    Thread.sleep( 1 );
                }
            }
            catch ( InterruptedException e )
            {
                if ( !shutdown.get() )
                {
                    LOGGER.warn( "DiskJournalWriter ignores to interrupt, to shutdown "
                        + "it call DiskJournalWriterTask::shutdown()", e );
                }
            }

            shutdownLatch.countDown();
        }

        public void shutdown()
        {
            shutdown.compareAndSet( false, true );
            diskJournalWriter.interrupt();
        }

    }

}

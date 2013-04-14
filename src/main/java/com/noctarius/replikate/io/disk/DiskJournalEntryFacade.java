package com.noctarius.replikate.io.disk;

import com.noctarius.replikate.JournalEntry;

class DiskJournalEntryFacade<V>
    extends DiskJournalEntry<V>
{

    final JournalEntry<V> wrappedEntry;

    public DiskJournalEntryFacade( JournalEntry<V> wrappedEntry )
    {
        super( wrappedEntry.getValue(), wrappedEntry.getType() );
        this.wrappedEntry = wrappedEntry;
    }

}
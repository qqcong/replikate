package com.github.noctarius.replikate.spi;

public interface JournalNamingStrategy
{

    String generate( long logNumber );

    boolean isJournal( String name );

    long extractLogNumber( String name );

}
package com.github.noctarius.waljdbc.exceptions;

public class JournalException
    extends RuntimeException
{

    private static final long serialVersionUID = 2480300208807598045L;

    public JournalException()
    {
        super();
    }

    public JournalException( String message, Throwable cause )
    {
        super( message, cause );
    }

    public JournalException( String message )
    {
        super( message );
    }

    public JournalException( Throwable cause )
    {
        super( cause );
    }

}
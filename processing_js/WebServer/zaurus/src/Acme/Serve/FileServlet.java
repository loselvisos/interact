// FileServlet - servlet similar to a standard httpd
//
// Copyright (C)1996,1998 by Jef Poskanzer <jef@acme.com>. All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions
// are met:
// 1. Redistributions of source code must retain the above copyright
//    notice, this list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright
//    notice, this list of conditions and the following disclaimer in the
//    documentation and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE AUTHOR AND CONTRIBUTORS ``AS IS'' AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
// OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
// HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
// LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
// OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
// SUCH DAMAGE.
//
// Visit the ACME Labs Java page for up-to-date versions of this and other
// fine Java utilities: http://www.acme.com/java/
// 
// All enhancments Copyright (C)1998,2000 by Dmitriy Rogatkin
// http://tjws.sourceforge.net

package Acme.Serve;

import java.io.*;
import java.util.*;
import java.text.*;
import javax.servlet.*;
import javax.servlet.http.*;

/// Servlet similar to a standard httpd.
// <P>
// Implements the "GET" and "HEAD" methods for files and directories.
// Handles index.html, index.htm, default.htm, default.html.
// Redirects directory URLs that lack a trailing /.
// Handles If-Modified-Since.
// <P>
// <A HREF="/resources/classes/Acme/Serve/FileServlet.java">Fetch the software.</A><BR>
// <A HREF="/resources/classes/Acme.tar.Z">Fetch the entire Acme package.</A>
// <P>
// @see Acme.Serve.Serve

// All enhancments Copyright (C)1998-2000 by Dmitriy Rogatkin
// this version is compatible with the latest JSDK 2.2
// http://tjws.sourceforge.net

public class FileServlet extends HttpServlet {

    // We keep a single throttle table for all instances of the servlet.
    // Normally there is only one instance; the exception is subclasses.
    static Acme.WildcardDictionary throttleTab = null;
    static final String[] DEFAULTINDEXPAGES = {"index.html", "index.htm", "default.htm", "default.html"};
    static final DecimalFormat lengthftm = new DecimalFormat("#");

	private static final boolean logenabled =
//		true;
		false;

    /// Constructor.
    public FileServlet()
    {
    }

    /// Constructor with throttling.
    // @param throttles filename containing throttle settings
    // @see ThrottledOutputStream
    public FileServlet( String throttles ) throws IOException
    {
        this();
        readThrottles( throttles );
    }

    private void readThrottles( String throttles ) throws IOException
    {
        Acme.WildcardDictionary newThrottleTab =
        ThrottledOutputStream.parseThrottleFile( throttles );
        if ( throttleTab == null )
            throttleTab = newThrottleTab;
        else {
            // Merge the new one into the old one.
            Enumeration keys = newThrottleTab.keys();
            Enumeration elements = newThrottleTab.elements();
            while ( keys.hasMoreElements() ) {
                Object key = keys.nextElement();
                Object element = elements.nextElement();
                throttleTab.put( key, element );
            }
        }
    }

    /// Returns a string containing information about the author, version, and
    // copyright of the servlet.
    public String getServletInfo()
    {
        return "servlet similar to a standard httpd";
    }


    /// Services a single request from the client.
    // @param req the servlet request
    // @param req the servlet response
    // @exception ServletException when an exception has occurred
    public void service( HttpServletRequest req, HttpServletResponse res ) throws ServletException, IOException
    {
        boolean headOnly;
        if ( req.getMethod().equalsIgnoreCase( "get" ) )
            headOnly = false;
        else if ( ! req.getMethod().equalsIgnoreCase( "head" ) )
            headOnly = true;
        else {
            res.sendError( HttpServletResponse.SC_NOT_IMPLEMENTED );
            return;
        }

		String path = req.getPathInfo();
		if (path != null && path.length() > 2) {
			path = path.replace( '\\', '/');
			if ( path.indexOf( "/../" ) >0 || path.endsWith( "/.." ) ) {
				res.sendError( HttpServletResponse.SC_FORBIDDEN );
				return;
			}
		}

        dispatchPathname( req, res, headOnly, path );
    }


    private void dispatchPathname( HttpServletRequest req, HttpServletResponse res, boolean headOnly, String path ) throws IOException
    {
		String filename = req.getPathTranslated()!=null?req.getPathTranslated().replace( '/', File.separatorChar ):"";
        if ( filename.length() > 0 && filename.charAt( filename.length() - 1 ) == File.separatorChar )
            filename = filename.substring( 0, filename.length() - 1 );
        File file = new File( filename );
		log("showing "+filename+" for path "+path);
        if ( file.exists() ) {
            if ( ! file.isDirectory() )
                serveFile( req, res, headOnly, path, file );
			else {
				log("showing dir "+file);
				if ( path.charAt( path.length() - 1 ) != '/' )
					redirectDirectory( req, res, path, file );
				else 					
					showIdexFile(req, res, headOnly, path, filename);
			}
		} else {
			for (int i=0; i<DEFAULTINDEXPAGES.length; i++) {
				if ( filename.endsWith( File.separator+DEFAULTINDEXPAGES[i] ) ) {
					showIdexFile(req, res, headOnly, path, file.getParent());
					return;
				}
			}
			res.sendError( HttpServletResponse.SC_NOT_FOUND );
		}
	}

	private void showIdexFile(HttpServletRequest req, HttpServletResponse res,
							  boolean headOnly, String path, String parent) throws IOException {
		log("showing index in directory "+parent);
		for (int i=0; i<DEFAULTINDEXPAGES.length; i++) {
			File indexFile = new File( parent, DEFAULTINDEXPAGES[i] );
			if ( indexFile.exists() ) {
				serveFile(req, res, headOnly, path, indexFile );
				return;
			}
		}
		// index not found
		serveDirectory(req, res, headOnly, path, new File(parent) );
	}
	
    private void serveFile( HttpServletRequest req, HttpServletResponse res, boolean headOnly, String path, File file ) throws IOException
    {
        log( "getting " + file );
        if ( ! file.canRead() ) {
            res.sendError( HttpServletResponse.SC_FORBIDDEN );
            return;
        }

        // Handle If-Modified-Since.
        res.setStatus( HttpServletResponse.SC_OK );
        long lastMod = file.lastModified();
        String ifModSinceStr = req.getHeader( "If-Modified-Since" );
        long ifModSince = -1;
        if ( ifModSinceStr != null ) {
            int semi = ifModSinceStr.indexOf( ';' );
            if ( semi != -1 )
                ifModSinceStr = ifModSinceStr.substring( 0, semi );
            try {
                ifModSince =
                DateFormat.getDateInstance().parse( ifModSinceStr ).getTime();
            } catch ( Exception ignore ) {
            }
        }
        if ( ifModSince != -1 && ifModSince == lastMod ) {
            res.setStatus( HttpServletResponse.SC_NOT_MODIFIED );
            headOnly = true;
        }

        res.setContentType( getServletContext().getMimeType( file.getName() ) );
        res.setContentLength( (int) file.length() );
        res.setDateHeader( "Last-modified", lastMod );
        OutputStream out = res.getOutputStream();
        if ( ! headOnly ) {
            // Check throttle.
            if ( throttleTab != null ) {
                ThrottleItem throttleItem =
                (ThrottleItem) throttleTab.get( path );
                if ( throttleItem != null ) {
                    // !!! Need to account for multiple simultaneous fetches.
                    out = new ThrottledOutputStream(
                                                   out, throttleItem.getMaxBps() );
                }
            }

            InputStream in = new FileInputStream( file );
            copyStream( in, out );
            in.close();
        }
        out.close();
    }

    /// Copy a file from in to out.
    // Sub-classes can override this in order to do filtering of some sort.
    public void copyStream( InputStream in, OutputStream out ) throws IOException
    {
        Acme.Utils.copyStream( in, out );
    }


    private void serveDirectory( HttpServletRequest req, HttpServletResponse res, boolean headOnly, String path, File file ) throws IOException
    {
        log( "indexing " + file );
        if ( ! file.canRead() ) {
            res.sendError( HttpServletResponse.SC_FORBIDDEN );
            return;
        }
        res.setStatus( HttpServletResponse.SC_OK );
        res.setContentType( "text/html" );
        OutputStream out = res.getOutputStream();
        if ( ! headOnly ) {
            PrintStream p = new PrintStream( new BufferedOutputStream( out ) );
            p.println( "<HTML><HEAD>" );
            p.println( "<TITLE>Index of " + path + "</TITLE>" );
            p.println( "</HEAD><BODY BGCOLOR=\"#F1D0F2\">" );
            p.println( "<H2>Index of " + path + "</H2>" );
            p.println( "<PRE>" );
            p.println( "mode         bytes  last-changed  name" );
            p.println( "<HR>" );
            String[] names = file.list();
            Acme.Utils.sortStrings( names );
            for ( int i = 0; i < names.length; ++i ) {
                File aFile = new File( file, names[i] );
                String aFileType;
                if ( aFile.isDirectory() )
                    aFileType = "d";
                else if ( aFile.isFile() )
                    aFileType = "-";
                else
                    aFileType = "?";
                String aFileRead = ( aFile.canRead() ? "r" : "-" );
                String aFileWrite = ( aFile.canWrite() ? "w" : "-" );
                String aFileExe = "-";
                String aFileSize = lengthftm.format( aFile.length() );
                while (aFileSize.length() < 12)
                    aFileSize = " "+aFileSize;
                String aFileDate =
                Acme.Utils.lsDateStr( new Date( aFile.lastModified() ) );
                while (aFileDate.length() < 14)
                    aFileDate += " ";
                String aFileDirsuf = ( aFile.isDirectory() ? "/" : "" );
                String aFileSuf = ( aFile.isDirectory() ? "/" : "" );
                p.println(
                         aFileType + aFileRead + aFileWrite + aFileExe +
                         "  " + aFileSize + "  " + aFileDate + "  " +
                         "<A HREF=\"" + names[i] + aFileDirsuf + "\">" +
                         names[i] + aFileSuf + "</A>" );
            }
            p.println( "</PRE>" );
            p.println( "<HR>" );
            Serve.Identification.writeAddress( p );
            p.println( "</BODY></HTML>" );
            p.flush();
        }
        out.close();
    }


    private void redirectDirectory( HttpServletRequest req, HttpServletResponse res, String path, File file ) throws IOException
    {
        log( "redirecting " + path );
        res.sendRedirect( path + "/" );
    }

    public void log(String msg)
    {
        if (logenabled)
            super.log(msg);
    }
}

/*******************************************************************************
 * Copyright (c) 2003, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ant.internal.ui.antsupport.logger.debug;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.Vector;

import org.apache.tools.ant.BuildEvent;
import org.apache.tools.ant.Location;
import org.apache.tools.ant.Target;
import org.apache.tools.ant.Task;
import org.eclipse.ant.internal.ui.antsupport.logger.RemoteAntBuildLogger;
import org.eclipse.ant.internal.ui.antsupport.logger.util.AntDebugUtil;
import org.eclipse.ant.internal.ui.antsupport.logger.util.DebugMessageIds;

/**
 * Parts adapted from org.eclipse.jdt.internal.junit.runner.RemoteTestRunner
 * A build logger that reports via a socket connection.
 * See DebugMessageIds and MessageIds for more information about the protocol.
 */
public class RemoteAntDebugBuildLogger extends RemoteAntBuildLogger {
	
	private ServerSocket fServerSocket;
	private Socket fRequestSocket;
	
	private PrintWriter fRequestWriter;
	
	private BufferedReader fRequestReader;
	
	protected boolean fStepIntoSuspend= false;
	
	protected boolean fClientSuspend= false;
	
	protected boolean fBuildStartedSuspend= false;
	
	private Stack fTasks= new Stack();
	private Task fCurrentTask;
	private Task fStepOverTask;
	private Task fStepOverTaskInterrupted;
	private Task fStepIntoTask;
	private Task fLastTaskFinished;
	
	private List fBreakpoints= null;
    
     private Map fTargetToBuildSequence= null;
     private Target fTargetToExecute= null;
     private Target fTargetExecuting= null;
	
	//properties set before execution
	private Map fInitialProperties= null;
	private Map fProperties= null;
	
	/**
	 * Request port to connect to.
	 * Used for debug connections
	 */
	protected int fRequestPort= -1;

	/**
	 * Reader thread that processes requests from the debug client.
	 */
	private class ReaderThread extends Thread {
		public ReaderThread() {
			super("ReaderThread"); //$NON-NLS-1$
			setDaemon(true);
		}

		public void run(){
			try { 
				String message= null; 
				while (fRequestReader != null) { 
					if ((message= fRequestReader.readLine()) != null) {
						
						if (message.startsWith(DebugMessageIds.STEP_INTO)){
							synchronized(RemoteAntDebugBuildLogger.this) {
							    fStepIntoSuspend= true;
							    fStepIntoTask= fCurrentTask;
								RemoteAntDebugBuildLogger.this.notifyAll();
							}
						} if (message.startsWith(DebugMessageIds.STEP_OVER)){
							synchronized(RemoteAntDebugBuildLogger.this) {
								fStepOverTask= fCurrentTask;
								RemoteAntDebugBuildLogger.this.notifyAll();
							}
						} else if (message.startsWith(DebugMessageIds.SUSPEND)) {
							synchronized(RemoteAntDebugBuildLogger.this) {
								fStepIntoTask= null;
								fStepOverTask= null;
								fStepOverTaskInterrupted= null;
								fClientSuspend= true;
							}
						} else if (message.startsWith(DebugMessageIds.RESUME)) {
							synchronized(RemoteAntDebugBuildLogger.this) {
								fStepIntoTask= null;
								fStepOverTask= null;
								fStepOverTaskInterrupted= null;
								RemoteAntDebugBuildLogger.this.notifyAll();
							}
						} else if (message.startsWith(DebugMessageIds.TERMINATE)) {
						    sendRequestResponse(DebugMessageIds.TERMINATED);
							shutDown();
						} else if (message.startsWith(DebugMessageIds.STACK)) {
							marshallStack();
						} else if (message.startsWith(DebugMessageIds.ADD_BREAKPOINT)) {
							addBreakpoint(message);
						} else if (message.startsWith(DebugMessageIds.REMOVE_BREAKPOINT)) {
							removeBreakpoint(message);
						}  else if (message.startsWith(DebugMessageIds.PROPERTIES)) {
							marshallProperties();
						}
					}
				} 
			} catch (Exception e) {
				RemoteAntDebugBuildLogger.this.shutDown();
			}
		}
	}
	
	private void requestConnect() {
		if (fDebugMode) {
			System.out.println("RemoteAntDebugBuildLogger: trying to connect" + fHost + ":" + fRequestPort); //$NON-NLS-1$ //$NON-NLS-2$
		}
		
		try{
			fRequestSocket= fServerSocket.accept();
			fRequestWriter= new PrintWriter(fRequestSocket.getOutputStream(), true);
			fRequestReader = new BufferedReader(new InputStreamReader(fRequestSocket.getInputStream()));
			
			ReaderThread readerThread= new ReaderThread();
			readerThread.setDaemon(true);
			readerThread.start();
			return;
		} catch(IOException e){
		}
		
		shutDown();
		//throw new BUildExection()
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.ant.internal.ui.antsupport.logger.RemoteAntBuildLogger#shutDown()
	 */
	protected void shutDown() {
		if (fRequestWriter != null) {
			fRequestWriter.close();
			fRequestWriter= null;
		}
		
		if (fRequestReader != null) {
			try {
				fRequestReader.close();
			} catch (IOException e) {
			}
			fRequestReader= null;
		}
		
		if (fRequestSocket != null) {
			try {
				fRequestSocket.close();	
			} catch(IOException e) {
			}
		}
		fRequestSocket= null;
		
		super.shutDown();
	}
	
	/* (non-Javadoc)
	 * @see org.apache.tools.ant.BuildListener#buildStarted(org.apache.tools.ant.BuildEvent)
	 */
	public synchronized void buildStarted(BuildEvent event) {
		super.buildStarted(event);
		marshalMessage(-1, DebugMessageIds.BUILD_STARTED);
		if (fRequestPort != -1) {
			try {
				fServerSocket= new ServerSocket(fRequestPort);
			} catch (IOException ioe) {
				//throw new buildexection();
				shutDown();
			}
			requestConnect();
		} else {
			shutDown();
		}
		fBuildStartedSuspend= true;
		waitIfSuspended();
	}
    
     private void initializeBuildSequenceInformation(BuildEvent event) {
         fTargetToBuildSequence= new HashMap();
         fTargetToExecute= AntDebugUtil.initializeBuildSequenceInformation(event, fTargetToBuildSequence);
     }
	
	/* (non-Javadoc)
	 * @see org.apache.tools.ant.BuildListener#taskStarted(org.apache.tools.ant.BuildEvent)
	 */
	public void taskStarted(BuildEvent event) {
		if (fInitialProperties == null) {//implicit or top level target does not fire targetStarted()
			fInitialProperties= event.getProject().getProperties();
		}
		super.taskStarted(event);
		fCurrentTask= event.getTask();
		fTasks.push(fCurrentTask);
		waitIfSuspended();
	}
	
	/* (non-Javadoc)
	 * @see org.apache.tools.ant.BuildListener#taskFinished(org.apache.tools.ant.BuildEvent)
	 */
	public void taskFinished(BuildEvent event) {
		super.taskFinished(event);
		fLastTaskFinished= (Task)fTasks.pop();
		fCurrentTask= null;
		waitIfSuspended();
	}
	
	private synchronized void waitIfSuspended() {
	    if (fCurrentTask != null) {
	        String detail= null;
	        boolean shouldSuspend= true;
	        RemoteAntBreakpoint breakpoint= breakpointAtLineNumber(fCurrentTask.getLocation());
	        if (breakpoint != null) {
	            detail= breakpoint.toMarshallString();
	            if (fStepOverTask != null) {
	            	fStepOverTaskInterrupted= fStepOverTask;
	            	fStepOverTask= null;
	            }
	        } else if (fStepIntoSuspend) {
	            detail= DebugMessageIds.STEP;
	            fStepIntoSuspend= false;
	        } else if (fLastTaskFinished != null && fLastTaskFinished == fStepOverTask) {
	        	//suspend as a step over has finished
	        	detail= DebugMessageIds.STEP;
	        	fStepOverTask= null;
	        } else if (fLastTaskFinished != null && fLastTaskFinished == fStepIntoTask) {
	        	//suspend as a task that was stepped into has finally completed
	        	 detail= DebugMessageIds.STEP;
	        	 fStepIntoTask= null;
	        } else if (fLastTaskFinished != null && fLastTaskFinished == fStepOverTaskInterrupted) {
	        	//suspend as a task that was stepped over but hit a breakpoint has finally completed
	        	 detail= DebugMessageIds.STEP;
	        	 fStepOverTaskInterrupted= null;
	        } else if (fClientSuspend) {
	            detail= DebugMessageIds.CLIENT_REQUEST;
	            fClientSuspend= false;
	        } else {
	            shouldSuspend= false;
	        }
	        if (shouldSuspend) {
	            StringBuffer message= new StringBuffer(DebugMessageIds.SUSPENDED);
	            message.append(detail);
	            sendRequestResponse(message.toString());
	            try {
	                wait();
	            } catch (InterruptedException e) {
	            }
	        }
	    } else if (fBuildStartedSuspend) {
	        try {
	            fBuildStartedSuspend= false;
	            wait();
	        } catch (InterruptedException e) {
	        }
	    }
	}

	private RemoteAntBreakpoint breakpointAtLineNumber(Location location) {
		if (fBreakpoints == null) {
			return null;
		}
		String fileName= AntDebugUtil.getFileName(location);
		int lineNumber= AntDebugUtil.getLineNumber(location);
		for (int i = 0; i < fBreakpoints.size(); i++) {
			RemoteAntBreakpoint breakpoint = (RemoteAntBreakpoint) fBreakpoints.get(i);
			if (breakpoint.isAt(fileName, lineNumber)) {
				return breakpoint;
			}
		}
		return null;
	}

	private void sendRequestResponse(String message) {
		if (fRequestWriter == null) {
			return;
		}
		
		fRequestWriter.println(message);
	}
	
	protected void marshallStack() {
	    StringBuffer stackRepresentation= new StringBuffer();
	    AntDebugUtil.marshalStack(stackRepresentation, fTasks, fTargetToExecute, fTargetExecuting, fTargetToBuildSequence);
	    sendRequestResponse(stackRepresentation.toString());
	}
	
	protected void marshallProperties() {
	    StringBuffer propertiesRepresentation= new StringBuffer();
        if (!fTasks.isEmpty()) {
            AntDebugUtil.marshallProperties(propertiesRepresentation, ((Task)fTasks.peek()).getProject(), fInitialProperties, fProperties, false);
            fProperties= ((Task)fTasks.peek()).getProject().getProperties();
        }
	    sendRequestResponse(propertiesRepresentation.toString());
	}
	
	protected void addBreakpoint(String breakpointRepresentation) {
		if (fBreakpoints == null) {
			fBreakpoints= new ArrayList();
		}
		RemoteAntBreakpoint newBreakpoint= new RemoteAntBreakpoint(breakpointRepresentation);
		if (!fBreakpoints.contains(newBreakpoint)) {
			fBreakpoints.add(newBreakpoint);	
		}
	}
	
	protected void removeBreakpoint(String breakpointRepresentation) {
		if (fBreakpoints == null) {
			return;
		} 
		RemoteAntBreakpoint equivalentBreakpoint= new RemoteAntBreakpoint(breakpointRepresentation);
		for (Iterator iter = fBreakpoints.iterator(); iter.hasNext(); ) {
			RemoteAntBreakpoint breakpoint = (RemoteAntBreakpoint) iter.next();
			if (breakpoint.equals(equivalentBreakpoint)) {
				iter.remove();
				return;
			}
		}
	}

	/* (non-Javadoc)
	 * @see org.apache.tools.ant.BuildListener#targetStarted(org.apache.tools.ant.BuildEvent)
	 */
	public void targetStarted(BuildEvent event) {
		if (fInitialProperties == null) {
			fInitialProperties= event.getProject().getProperties();
		}
		if (fTargetToBuildSequence == null) {
			initializeBuildSequenceInformation(event);
		}
		super.targetStarted(event);
		fTargetExecuting= event.getTarget();
		if (event.getTarget().equals(fTargetToExecute)) {
		    //the dependancies of the target to execute have been met
		    //prepare for the next target
		    Vector targets= (Vector) event.getProject().getReference("eclipse.ant.targetVector"); //$NON-NLS-1$
		    if (!targets.isEmpty()) {
		        fTargetToExecute= (Target) event.getProject().getTargets().get(targets.remove(0));
		    } else {
		        fTargetToExecute= null;
            }
		}
	}
    
    public void configure(Map userProperties) {
       super.configure(userProperties);
       String requestPortProperty= (String) userProperties.remove("eclipse.connect.request_port"); //$NON-NLS-1$
        if (requestPortProperty != null) {
            fRequestPort= Integer.parseInt(requestPortProperty);
        }
    } 
}

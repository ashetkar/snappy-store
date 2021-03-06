/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package sql.sqlCallback.asyncEventListener;

import hydra.Log;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import sql.ClientDiscDBManager;
import sql.SQLHelper;
import sql.sqlCallback.SQLAsyncEventListenerTest;
import util.TestException;
import util.TestHelper;

import com.pivotal.gemfirexd.callbacks.Event;

public class PortfolioAsyncListener extends AbstractAsyncListener {
	static String delete = "delete from trade.portfolio where cid=? and sid=?";
	static String insert = "insert into trade.portfolio values (?,?,?,?,?,?)";
	
	private Event failedEvent = null;
	
	private static ThreadLocal<PreparedStatement> insertStmt = new ThreadLocal<PreparedStatement> () {		
		protected PreparedStatement initialValue()  {
			
			PreparedStatement stmt = null;
			try {
				stmt = dConn.get().prepareStatement(insert);
			}catch (SQLException se) {
				SQLHelper.handleSQLException(se);
			}
			return stmt;
		}
	};
	
	private static ThreadLocal<PreparedStatement> deleteStmt = new ThreadLocal<PreparedStatement> () {		
		protected PreparedStatement initialValue()  {
			PreparedStatement stmt = null;
			try {
				stmt = dConn.get().prepareStatement(delete);
			}catch (SQLException se) {
				SQLHelper.handleSQLException(se);
			}
			return stmt;
		}
	};
	
	private static ThreadLocal<Statement> stmt = new ThreadLocal<Statement> () {		
		protected Statement initialValue() {
			Statement s = null;
			try {
				s = dConn.get().createStatement();
			}catch (SQLException se) {
				SQLHelper.handleSQLException(se);
			}
			return s;
		}
	};

  @Override
  public boolean processEvents(List<Event> events) {
    Log.getLogWriter().info("start processing the list of async events");
    for (Event event : events){
      if (failedEvent != null) {
        if (!isSame(failedEvent, event)) {
          if (!event.isPossibleDuplicate()) Log.getLogWriter().warning("an already processed " +
              "event does not have isPossibleDuplicate() set to true");
          else Log.getLogWriter().info("this event has been processed before, do not retry");
          continue;
        } else {
          failedEvent = null; //reset the flag.
        }
      } 
      
      boolean success = processEvent(event);
      if (!success) {
        if (testUniqueKeys) {
          failedEvent = event;
          //restart from the batching from previously failed one, should not have out of order delivery to queue
          Log.getLogWriter().info("test unique key, will retry the failed event");
          return false;
        } else {
          Log.getLogWriter().info("the event does not succeed in back end, but will not continue to retry" +
              "to block the queue");
          //in the multiple listener case, insert p, insert c, delete c, delete p
          //if delete c and insert c are executed out of order in back end db, 
          //the delete p will continue to be retried and will block all the following events for the table.
        }
      }
    }
    return true;
  }
  
  public boolean processEvent(Event event) {
    try {
      Event.Type type = event.getType();
      switch (type) {
      case AFTER_UPDATE:
        update(event);
        break;
      case AFTER_INSERT:
        insert(event);
        break;
      case AFTER_DELETE:
        delete(event);
        break;
      default:
        Log.getLogWriter().warning("TestException: AsyncEventListener got a 'none_after' callback event: " + type);
      }   
      dConn.get().commit(); //do not hold the lock  
    }catch(SQLException se){
      SQLHelper.printSQLException(se);
      if (se.getSQLState().equals("08006") || se.getSQLState().equals("08003") || 
          se.getSQLState().equals("08001")) {
        Log.getLogWriter().info("connection is lost");
        Connection conn =null;
        try {
          conn = ClientDiscDBManager.getConnection(); 
          //may need to use url to get the correct db connection
        } catch (SQLException ex) {
          SQLHelper.handleSQLException(ex);
        }
        dConn.set(conn); //provide new connection
        resetStatements();
      } else 
        Log.getLogWriter().warning("AsyncEventListener Test gets exception\n" + 
            TestHelper.getStackTrace(se)); 
      return false;
    } 
    return true;
  } 
	
	private void doInsert(List<Object> oldRow, List<Object> newRow, 
			boolean isPossibleDuplicate) throws SQLException {
	  /*
	   * trade.Portfolio table fields
	   *   int cid;
	   *   int sid;
	   *   int qty;
	   *   int availQty;
	   *   BigDecimal subTotal;
	   *   int tid; //for update or delete unique records to the thread
	   */
		int count = 0;
		if (oldRow != null) throw new TestException("BEFORE_INSERT in portfolioListener " +
				"is invoked, but oldRow is not null: " + oldRow);
	
		insertStmt.get().setInt(1, (Integer)( newRow.get(0)));
		insertStmt.get().setInt(2, (Integer) newRow.get(1));
		insertStmt.get().setInt(3, (Integer) newRow.get(2));
		insertStmt.get().setInt(4, (Integer) newRow.get(3));
		insertStmt.get().setBigDecimal(5, (BigDecimal) newRow.get(4));
		insertStmt.get().setInt(6, (Integer) newRow.get(5));
		Log.getLogWriter().info("inserting into portfolio in derby " +
				"cid is " + (Integer)( newRow.get(0)) +
				" sid is " + (Integer) newRow.get(1) +
				" qty is " +  newRow.get(2) +
				" availQty is " + newRow.get(3) + 
				" subTotal is " + (BigDecimal) newRow.get(4) +
				" tid is " + (Integer) newRow.get(5));

		count = insertStmt.get().executeUpdate();
		Log.getLogWriter().info("inserted " + count + " row");

		if (count !=1 && !isPossibleDuplicate && testUniqueKeys) {
		  SQLAsyncEventListenerTest.testException = new TestException("insert from listener does not " +
				"insert the corresponding row in back_end, inserted row is " + count);
		  throw SQLAsyncEventListenerTest.testException;
		}
	}
		
	protected void insert (Event event) throws SQLException {
		List<Object> oldRow = event.getOldRow();
		List<Object> newRow = event.getNewRow();
		boolean isPossibleDuplicate = event.isPossibleDuplicate();		
		//for HA case, before insert check if record has been inserted already to avoid duplicate
		if (isPossibleDuplicate) {
			ResultSet rs = stmt.get().executeQuery("select * from trade.portfolio where cid = "
					+ (Integer)event.getPrimaryKey()[0] + " and sid = " 
					+ (Integer)event.getPrimaryKey()[1]);
			if (rs.next()) {
				Log.getLogWriter().info("this row has been inserted already " + newRow);
				//if it is there, do not insert again
			} else {
				doInsert(oldRow, newRow, isPossibleDuplicate);
			}
		} else doInsert(oldRow, newRow, isPossibleDuplicate);
			
	}	
	
	protected void delete (Event event) throws SQLException {
		List<Object> newRow = event.getNewRow();
		boolean isPossibleDuplicate = event.isPossibleDuplicate();		
		int count =0;
		if (newRow != null) throw new TestException("BEFORE_DELETE is invoked, but " +
				"newRow is not null: " + newRow);
		
		deleteStmt.get().setInt(1, (Integer)event.getPrimaryKey()[0]); //through primary key
		deleteStmt.get().setInt(2, (Integer)event.getPrimaryKey()[1]); //through primary key
		Log.getLogWriter().info("deleting from portfolio in derby where cid ="
				+ (Integer)event.getPrimaryKey()[0] + " and sid = " 
				+ (Integer)event.getPrimaryKey()[1]);
		
		count = deleteStmt.get().executeUpdate();
		Log.getLogWriter().info("deleted " + count + " row");

		Log.getLogWriter().info("deleted " + count + " row");
		if (count !=1 && !isPossibleDuplicate && testUniqueKeys) {
		  SQLAsyncEventListenerTest.testException = new TestException("delete from listener does not " +
				"delete the corresponding row in back_end, deleted row is " + count);
		  throw SQLAsyncEventListenerTest.testException;
		}
	}
	
	protected void update(Event event) throws SQLException{
		boolean isPossibleDuplicate = event.isPossibleDuplicate();		
		int count =0;
		String sql = getSql(event);
		//add where clause
		sql +=" where cid = " + event.getPrimaryKey()[0] +
		  "and sid = " + event.getPrimaryKey()[1];
		Log.getLogWriter().info("update to derby: " + sql);

		count = stmt.get().executeUpdate(sql);
		Log.getLogWriter().info("updated " + count + " row");

		if (count !=1 && !isPossibleDuplicate && testUniqueKeys) {
		  SQLAsyncEventListenerTest.testException = new TestException("update from listener does not " +
				"update the corresponding row in back_end, updated row is " + count);
		  throw SQLAsyncEventListenerTest.testException;
		}
	}	
	
  protected void resetStatements() {
    resetInsert();
    resetDelete();
    resetStmt();
  }
  
  protected void resetInsert() {
    PreparedStatement stmt = null;
    try {
      stmt = dConn.get().prepareStatement(insert);
    }catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    insertStmt.set(stmt);
  }
  
  protected void resetDelete() {
    PreparedStatement stmt = null;
    try {
      stmt = dConn.get().prepareStatement(delete);
    }catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    deleteStmt.set(stmt);
  }
  
  protected void resetStmt() {
    Statement s = null;
    try {
      s = dConn.get().createStatement();
    }catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    stmt.set(s);
  }
  
  protected static ThreadLocal<Connection> dConn = new ThreadLocal<Connection>()  {
    protected Connection initialValue()  {
      Connection dConn =null;
      try {
        dConn = ClientDiscDBManager.getConnection(); 
        //may need to use url to get the correct db connection
      } catch (SQLException se) {
        SQLHelper.handleSQLException(se);
      }
      return dConn;
    }
  };  
}

package net.sf.borg.model.ical;

import java.io.InputStream;
import java.io.Writer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlRootElement;

import net.sf.borg.common.Errmsg;
import net.sf.borg.common.Prefs;
import net.sf.borg.model.AppointmentModel;
import net.sf.borg.model.Model;
import net.sf.borg.model.TaskModel;
import net.sf.borg.model.db.DBHelper;
import net.sf.borg.model.db.jdbc.JdbcDBUpgrader;
import net.sf.borg.model.entity.Appointment;
import net.sf.borg.model.ical.SyncEvent.ObjectType;

/**
 * class to track all appointment model changes since the last sync it will
 * persist this information to a file
 */
public class SyncLog extends Model implements Model.Listener, Prefs.Listener {

	static private SyncLog singleton = null;
	
	private boolean processUpdates = true;

	static public SyncLog getReference() {
		if (singleton == null)
			singleton = new SyncLog();
		return singleton;
	}

	public SyncLog() {
		
		setProcessUpdates(CalDav.isSyncing());

		new JdbcDBUpgrader(
				"select id from syncmap",
				"CREATE CACHED TABLE syncmap (id integer NOT NULL,uid longvarchar, objtype varchar(25) NOT NULL,action varchar(25) NOT NULL,PRIMARY KEY (id,objtype))")
				.upgrade();
		AppointmentModel.getReference().addListener(this);
		TaskModel.getReference().addListener(this);
		Prefs.addListener(this);
	}
	

	@Override
	public void update(ChangeEvent borgEvent) {
		
		if( !isProcessUpdates())
			return;
		
		if (borgEvent == null || borgEvent.getObject() == null || borgEvent.getAction() == null)
			return;
		
		try {

			Object obj = borgEvent.getObject();
			SyncEvent newEvent = new SyncEvent();
			if( obj instanceof Appointment )
			{
				newEvent.setId(new Integer(((Appointment)obj).getKey()));
				newEvent.setObjectType(ObjectType.APPOINTMENT);
				
				// use the URL first. If null, user the UID
				Appointment ap = (Appointment) obj;
				if( ap.getUrl() != null)	
				{
					newEvent.setUid(((Appointment)obj).getUrl());
				}
				else
				{
					newEvent.setUid(((Appointment)obj).getUid());
				}
				
			}
			else
			{
				return;
			}
			/* ignore Task/Project objects for now - the sync is just for CALDAV appointments
			else if( obj instanceof Task)
			{
				newEvent.setId(new Integer(((Task)obj).getKey()));
				newEvent.setObjectType(ObjectType.TASK);
				newEvent.setUid("na");
			}
			else if( obj instanceof Subtask)
			{
				newEvent.setId(new Integer(((Subtask)obj).getKey()));
				newEvent.setObjectType(ObjectType.SUBTASK);
				newEvent.setUid("na");
			}
			else if( obj instanceof Project)
			{
				newEvent.setId(new Integer(((Project)obj).getKey()));
				newEvent.setObjectType(ObjectType.PROJECT);
				newEvent.setUid("na");
			}
			*/
			newEvent.setAction(borgEvent.getAction());
			
			Integer id = newEvent.getId();
			ObjectType type = newEvent.getObjectType();
			SyncEvent existingEvent = get(id.intValue(), type);
			String uid = newEvent.getUid();

			// any condition not listed is either a no-op or cannot occur
			if (existingEvent == null) {
				this.insert(newEvent);
			} else {

				if (existingEvent.getAction() == ChangeEvent.ChangeAction.ADD
						&& newEvent.getAction() == ChangeEvent.ChangeAction.DELETE) {
					this.delete(id.intValue(), type);
				} else if (existingEvent.getAction() == ChangeEvent.ChangeAction.CHANGE
						&& newEvent.getAction() == ChangeEvent.ChangeAction.DELETE) {
					SyncEvent event = new SyncEvent(id, uid,
							ChangeEvent.ChangeAction.DELETE, type);
					this.delete(id.intValue(), type);
					this.insert(event);
				} else if (existingEvent.getAction() == ChangeEvent.ChangeAction.DELETE
						&& newEvent.getAction() == ChangeEvent.ChangeAction.ADD) {
					SyncEvent event = new SyncEvent(id, uid,
							ChangeEvent.ChangeAction.CHANGE, type);
					this.delete(id.intValue(), type);
					this.insert(event);
				}

			}
		} catch (Exception e) {
			Errmsg.getErrorHandler().errmsg(e);
		}

	}

	private static SyncEvent createFrom(ResultSet r) throws SQLException {
		int id = r.getInt("id");
		ChangeEvent.ChangeAction action = ChangeEvent.ChangeAction.valueOf(r
				.getString("action"));
		String uid = r.getString("uid");
		String type = r.getString("objtype");
		ObjectType otype = ObjectType.valueOf(type);
		SyncEvent event = new SyncEvent(new Integer(id), uid, action, otype);
		return event;
	}

	public SyncEvent get(int id, ObjectType type) throws Exception {

		SyncEvent ret = null;

		PreparedStatement stmt = DBHelper.getController().getConnection().prepareStatement(
				"SELECT * FROM syncmap WHERE id = ? and objtype = ?");
		stmt.setInt(1, id);
		stmt.setString(2, type.toString());

		ResultSet r = null;
		try {
			r = stmt.executeQuery();
			if (r.next()) {
				ret = createFrom(r);
			}
			return ret;
		} finally {
			if (r != null)
				r.close();
			stmt.close();
		}
	}

	public List<SyncEvent> getAll() throws Exception {

		List<SyncEvent> ret = new ArrayList<SyncEvent>();

		PreparedStatement stmt = DBHelper.getController().getConnection().prepareStatement(
				"SELECT * FROM syncmap");

		ResultSet r = null;
		try {
			r = stmt.executeQuery();
			while (r.next()) {
				ret.add(createFrom(r));
			}
			return ret;
		} finally {
			if (r != null)
				r.close();
			if (stmt != null)
				stmt.close();
		}
	}

	public void insert(SyncEvent event) throws Exception {
		PreparedStatement stmt = DBHelper.getController().getConnection().prepareStatement(
				"INSERT INTO syncmap ( id, uid, action, objtype) " + " VALUES " + "( ?, ?, ?, ?)");

		stmt.setInt(1, event.getId().intValue());
		stmt.setString(2, event.getUid().toString());
		stmt.setString(3, event.getAction().toString());
		stmt.setString(4, event.getObjectType().toString());
		stmt.executeUpdate();
		stmt.close();
		
		this.refreshListeners();

	}

	public void delete(int id, ObjectType type) throws Exception {
		PreparedStatement stmt = DBHelper.getController().getConnection().prepareStatement(
				"DELETE FROM syncmap WHERE id = ? and objtype = ?");

		stmt.setInt(1, id);
		stmt.setString(2, type.toString());
		stmt.executeUpdate();
		stmt.close();

		this.refreshListeners();

	}

	public void deleteAll() throws Exception {
		PreparedStatement stmt = DBHelper.getController().getConnection().prepareStatement(
				"DELETE FROM syncmap");

		stmt.executeUpdate();
		stmt.close();
		
		this.refreshListeners();


	}
	
	@XmlRootElement(name = "SYNCMAP")
	private static class XmlContainer {
		public Collection<SyncEvent> SyncEvents;
	}

	@Override
	public void export(Writer fw) throws Exception {
		JAXBContext jc = JAXBContext.newInstance(XmlContainer.class);
		Marshaller m = jc.createMarshaller();
		XmlContainer container = new XmlContainer();
		container.SyncEvents = getAll();
		m.marshal(container, fw);
	}

	@Override
	public void importXml(InputStream is) throws Exception {
		JAXBContext jc = JAXBContext.newInstance(XmlContainer.class);
		Unmarshaller u = jc.createUnmarshaller();

		XmlContainer container = (XmlContainer) u
				.unmarshal(is);
		
		if( container.SyncEvents == null)
			return;

		for (SyncEvent evt : container.SyncEvents) {
			insert(evt);
		}

	}

	@Override
	public String getExportName() {
		return "SYNCMAP";
	}

	@Override
	public String getInfo() throws Exception {
		return "Synclogs: " + getAll().size();
	}

	public boolean isProcessUpdates() {
		return processUpdates;
	}

	public void setProcessUpdates(boolean processUpdates) {
		this.processUpdates = processUpdates;
	}

	@Override
	public void prefsChanged() {

		setProcessUpdates(CalDav.isSyncing());
		
	}

}

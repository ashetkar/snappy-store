SET CURRENT SCHEMA=SEC_OWNER;

INSERT
INTO SECM_LOCATION 
  (
    LOCATION_CODE,
    LOCATION_NAME,
    DATA_ARCHIVE,
	TIME_ZONE,
	TIME_FORMAT,
	ONBOARD_DATE,
	END_DATE,
	LAST_UPDATE_BY,
	LAST_UPDATE_DATE
  )
  VALUES
  (
    'EMEA',
    'EMEA',
    360,
	'GMT',
	'DD-MM-YYYY',
	CURRENT_TIMESTAMP,
	TIMESTAMP('2020-03-19', '00:00:00'),
	'SEC_SYS',
	CURRENT_TIMESTAMP
  );
INSERT
INTO SECM_LOCATION 
  (
    LOCATION_CODE,
    LOCATION_NAME,
    DATA_ARCHIVE,
	TIME_ZONE,
	TIME_FORMAT,
	ONBOARD_DATE,
	END_DATE,
	LAST_UPDATE_BY,
	LAST_UPDATE_DATE
  )
  VALUES
  (
    'UNKNOWN',
    'N/A',
    360,
	'GMT',
	'DD-MM-YYYY',
	CURRENT_TIMESTAMP,
	TIMESTAMP('2020-03-19', '00:00:00'),
	'SEC_SYS',
	CURRENT_TIMESTAMP
  );

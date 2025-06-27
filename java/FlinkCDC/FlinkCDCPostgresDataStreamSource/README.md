


• wal_level=logical - Enables logical decoding for CDC
• max_wal_senders=10 - Allows up to 10 concurrent replication connections
• max_replication_slots=10 - Allows up to 10 replication slots
• shared_preload_libraries=pgoutput - Loads the pgoutput plugin needed for logical replication
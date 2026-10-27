# Deployment Guide

## Vercel + Supabase

SQLite cannot be used on Vercel — serverless functions have a read-only filesystem. Use the Supabase backend instead.

### 1. Set up Supabase

1. Create a project at [supabase.com](https://supabase.com)
2. Go to **SQL Editor** and run the contents of `supabase/schema.sql`

This creates the tables, indexes, and two RPC functions (`truncate_table` and `get_scheduled_departures`) that the app depends on.

### 2. Load GTFS data

Run this locally once to populate your Supabase database with NTA static schedule data (~50 MB download, a few minutes to import):

```bash
STORAGE_BACKEND=supabase \
SUPABASE_URL=https://your-project.supabase.co \
SUPABASE_ANON_KEY=your_service_role_key \
npm run load-gtfs
```

> Use the **service_role** key here, not the anon key. It bypasses Row Level Security, which is required for bulk inserts.

Re-run this whenever you want to refresh the timetable data (NTA publishes updates periodically).

### 3. Deploy to Vercel

1. Push the repo to GitHub
2. Import it in the [Vercel dashboard](https://vercel.com/new)
3. Set the following environment variables:

| Variable | Value |
|---|---|
| `NTA_API_KEY` | Your key from [developer.nationaltransport.ie](https://developer.nationaltransport.ie) |
| `STORAGE_BACKEND` | `supabase` |
| `SUPABASE_URL` | `https://your-project.supabase.co` |
| `SUPABASE_ANON_KEY` | Your **anon** key (safe for public reads) |

4. Deploy.

> The **anon** key is correct for the deployed app. Only `load-gtfs` needs the service_role key.

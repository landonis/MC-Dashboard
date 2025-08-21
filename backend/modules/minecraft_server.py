@minecraft_server_bp.route('/journal', methods=['GET'])
@admin_required
def get_server_journal():
    """Get Minecraft server journal entries with pagination support"""
    try:
        # Get query parameters
        lines = request.args.get('lines', 100, type=int)
        since = request.args.get('since', None)  # ISO timestamp or systemd time format
        follow = request.args.get('follow', False, type=bool)
        priority = request.args.get('priority', None)  # err, warning, info, debug
        
        # Validate lines parameter (reasonable limits)
        lines = max(1, min(lines, 2000))  # Between 1 and 2000 lines
        
        # Build journalctl command
        cmd_parts = ["/usr/bin/journalctl", "-u", "minecraft.service", "--no-pager"]
        
        # Add lines limit
        cmd_parts.extend(["-n", str(lines)])
        
        # Add since parameter if provided
        if since:
            cmd_parts.extend(["--since", since])
        
        # Add priority filter if provided
        if priority:
            priority_map = {
                'err': '3',
                'warning': '4', 
                'info': '6',
                'debug': '7'
            }
            if priority in priority_map:
                cmd_parts.extend(["-p", priority_map[priority]])
        
        # Add output format for structured data
        cmd_parts.extend(["--output", "json"])
        
        # Execute command
        cmd = " ".join(cmd_parts)
        result = run_command(cmd)
        
        if not result['success']:
            return jsonify({
                'error': f'Failed to get journal entries: {result["stderr"]}',
                'entries': []
            }), 500
        
        # Parse JSON entries
        entries = []
        for line in result['stdout'].strip().split('\n'):
            if line.strip():
                try:
                    entry = json.loads(line)
                    # Extract relevant fields
                    parsed_entry = {
                        'timestamp': entry.get('__REALTIME_TIMESTAMP', ''),
                        'message': entry.get('MESSAGE', ''),
                        'priority': entry.get('PRIORITY', ''),
                        'unit': entry.get('_SYSTEMD_UNIT', ''),
                        'pid': entry.get('_PID', ''),
                        'cursor': entry.get('__CURSOR', '')  # For pagination
                    }
                    
                    # Convert timestamp to readable format
                    if parsed_entry['timestamp']:
                        try:
                            # Convert microseconds to seconds
                            timestamp_sec = int(parsed_entry['timestamp']) / 1000000
                            dt = datetime.fromtimestamp(timestamp_sec)
                            parsed_entry['formatted_time'] = dt.strftime('%Y-%m-%d %H:%M:%S')
                        except (ValueError, TypeError):
                            parsed_entry['formatted_time'] = 'Invalid timestamp'
                    
                    # Map priority numbers to readable levels
                    priority_map = {
                        '0': 'emergency', '1': 'alert', '2': 'critical', '3': 'error',
                        '4': 'warning', '5': 'notice', '6': 'info', '7': 'debug'
                    }
                    parsed_entry['level'] = priority_map.get(parsed_entry['priority'], 'unknown')
                    
                    entries.append(parsed_entry)
                    
                except json.JSONDecodeError:
                    # Skip invalid JSON lines
                    continue
        
        # Get total entry count (approximate)
        count_cmd = "/usr/bin/journalctl -u minecraft.service --no-pager | /usr/bin/wc -l"
        count_result = run_command(count_cmd)
        total_entries = 0
        if count_result['success']:
            try:
                total_entries = int(count_result['stdout'].strip())
            except ValueError:
                pass
        
        return jsonify({
            'entries': entries,
            'total_entries': total_entries,
            'requested_lines': lines,
            'returned_lines': len(entries),
            'has_more': len(entries) == lines,  # Indicates if there might be more entries
            'last_cursor': entries[-1]['cursor'] if entries else None
        })
    
    except Exception as e:
        logger.error(f"Get journal error: {str(e)}")
        return jsonify({
            'error': f'Failed to get journal entries: {str(e)}',
            'entries': []
        }), 500


@minecraft_server_bp.route('/journal/stream', methods=['GET'])
@admin_required 
def stream_server_journal():
    """Stream live journal entries (for real-time monitoring)"""
    try:
        def generate():
            # Start streaming from journalctl
            cmd = "/usr/bin/journalctl -u minecraft.service -f --output=json --no-pager"
            process = subprocess.Popen(
                cmd,
                shell=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                bufsize=1,
                universal_newlines=True
            )
            
            try:
                for line in iter(process.stdout.readline, ''):
                    if line.strip():
                        try:
                            entry = json.loads(line.strip())
                            parsed_entry = {
                                'timestamp': entry.get('__REALTIME_TIMESTAMP', ''),
                                'message': entry.get('MESSAGE', ''),
                                'priority': entry.get('PRIORITY', ''),
                                'unit': entry.get('_SYSTEMD_UNIT', ''),
                                'pid': entry.get('_PID', ''),
                            }
                            
                            # Convert timestamp
                            if parsed_entry['timestamp']:
                                try:
                                    timestamp_sec = int(parsed_entry['timestamp']) / 1000000
                                    dt = datetime.fromtimestamp(timestamp_sec)
                                    parsed_entry['formatted_time'] = dt.strftime('%Y-%m-%d %H:%M:%S')
                                except (ValueError, TypeError):
                                    parsed_entry['formatted_time'] = 'Invalid timestamp'
                            
                            yield f"data: {json.dumps(parsed_entry)}\n\n"
                            
                        except json.JSONDecodeError:
                            continue
            finally:
                process.terminate()
                
        return app.response_class(
            generate(),
            mimetype='text/plain'  # or 'text/event-stream' for SSE
        )
        
    except Exception as e:
        logger.error(f"Stream journal error: {str(e)}")
        return jsonify({'error': f'Failed to stream journal: {str(e)}'}), 500

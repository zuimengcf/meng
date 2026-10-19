#!/usr/bin/python3
# vim:set fileencoding=utf-8 et sts=4 sw=4:
#
# anthy-unicode - Anthy is a library for Japanese text input.
#
# Copyright © 2026 Takao Fujiwara <takao.fujiwara1@gmail.com>
#
# This library is free software; you can redistribute it and/or
# modify it under the terms of the GNU Lesser General Public
# License as published by the Free Software Foundation; either
# version 2.1 of the License, or (at your option) any later version.
#
# This library is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
# Lesser General Public License for more details.
#
# You should have received a copy of the GNU Lesser General Public
# License along with this library; if not, write to the Free Software
# Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA


import json
import os
import shutil
import subprocess
import sys

# Set the previous anthy-unicode version from `git tag -l` command.
ANTHY_PREV_VERSION = os.getenv('ANTHY_PREV_VERSION')
ANTHY_PREV_DATE='20190412'
BUILD_ROOT = os.getenv('MESON_PROJECT_BUILD_ROOT', '.')
SOURCE_ROOT = os.getenv('MESON_PROJECT_SOURCE_ROOT', '.')
DIST_ROOT = os.getenv('MESON_PROJECT_DIST_ROOT', '.')

cmd = ['meson', 'introspect', '--projectinfo', BUILD_ROOT]
info = None
try:
    result = subprocess.run(cmd, text=True, check=True,
                            stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT,
                            cwd=BUILD_ROOT).stdout
    info = json.loads(result)
except subprocess.CalledProcessError as e:
    print(f'Failed to run meson introspect: {e}', file=sys.stderr)
    sys.exit(1)
except json.JSONDecodeError as e:
    print(f'Failed to decode JSON from meson introspect: {e}',
          file=sys.stderr)
    sys.exit(1)

os.chdir(SOURCE_ROOT)
if not os.path.isdir('.git'):
    print(f'{SOURCE_ROOT}/.git is not a directory', file=sys.stderr)
    sys.exit(0)

name_str = info.get('descriptive_name')
version_str = info.get('version')
if name_str == None or version_str == None:
    print(f'You should run meson setup {BUILD_ROOT}', file=sys.stderr)
    sys.exit(1)

versions = version_str.split('.')
major_version = int(versions[0])
minor_version = int(versions[1])
micro_version = int(versions[2])
if ANTHY_PREV_VERSION == None:
    prev_micro_version_str = ''
    if micro_version > 0:
        prev_micro_version = micro_version - 1
        prev_micro_version_str = '%d' % prev_micro_version
    else:
        # Set the fixed tag until the official version is available.
        prev_micro_version_str = '%d.%s' % (micro_version, ANTHY_PREV_DATE)
    ANTHY_PREV_VERSION = '%d.%d.%s' % (major_version,
                                       minor_version,
                                       prev_micro_version_str)
subprocess.run('git log --name-status --date=iso > "{0}/{1}"'.format(
               DIST_ROOT, 'ChangeLog'),
               shell = True)
subprocess.run('echo "Changes in {0} {1}" > "{2}/{3}"'.format(
               name_str, version_str,
               DIST_ROOT, 'NEWS'),
               shell = True)
subprocess.run('echo "" >> "{0}/{1}"'.format(
               DIST_ROOT, 'NEWS'),
               shell = True)
subprocess.run('git shortlog {0}...{1} >> "{2}/{3}"'.format(
               ANTHY_PREV_VERSION, version_str,
               DIST_ROOT, 'NEWS'),
               shell = True)
subprocess.run('echo "" >> "{0}/{1}"'.format(
               DIST_ROOT, 'NEWS'),
               shell = True)
subprocess.run('git log {0}...{1} {2} >> "{3}/{4}"'.format(
               ANTHY_PREV_VERSION, version_str,
               '--reverse --pretty=format:"%s (%an) %h"',
               DIST_ROOT, 'NEWS'),
               shell = True)

shutil.copy2('autogen.sh', f'{DIST_ROOT}')
# Do you wish to copy configure and Makefile.in ?

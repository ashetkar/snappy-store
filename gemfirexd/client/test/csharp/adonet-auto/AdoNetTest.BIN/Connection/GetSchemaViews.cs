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
 
using System;
using System.Data;
using System.Data.Common;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading;
using Pivotal.Data.GemFireXD;

namespace AdoNetTest.BIN.Connection
{
    /// <summary>
    /// Retrieves schema definition and data for all defined Views
    /// </summary>
    class GetSchemaViews : GFXDTest
    {
        public GetSchemaViews(ManualResetEvent resetEvent)
            : base(resetEvent)
        {
        }

        public override void Run(object context)
        {
            DbController dbc = null;
            try
            {
                Log(DbDefault.GetCreateViewStatement(Relation.CUSTOMER_ADDRESS));

                dbc = new DbController(Connection);
                dbc.CreateView(Relation.CUSTOMER_ADDRESS);

                DataTable table = Connection.GetSchema("Views");

                if (table.Rows.Count <= 0)
                    Fail("GetSchema('Views') did not return information.");


                table.Clear();
                Command.CommandText = "SELECT * FROM " + Relation.CUSTOMER_ADDRESS.ToString();
                DataAdapter.Fill(table);
                                
                ParseDataTable(table);
            }
            catch (Exception e)
            {
                Fail(e);
            }
            finally
            {
                dbc.DropView(Relation.CUSTOMER_ADDRESS.ToString());
                base.Run(context);
            }
        }
    }
}
